package com.brajmohan.wallettransfer.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.brajmohan.wallettransfer.dto.TransferRequest;
import com.brajmohan.wallettransfer.dto.TransferResponse;
import com.brajmohan.wallettransfer.dto.WalletResponse;
import com.brajmohan.wallettransfer.exception.IdempotencyConflictException;
import com.brajmohan.wallettransfer.exception.InvalidTransferException;
import com.brajmohan.wallettransfer.exception.TransferNotFoundException;
import com.brajmohan.wallettransfer.metrics.DomainMetrics;

/**
 * The idempotency key is claimed in the same transaction as the ledger
 * movement, so there's no window where a concurrent retry can see "no key
 * yet" and double-debit. Balance changes are a single conditional UPDATE,
 * never read-subtract-write, so there's no lost-update race either.
 *
 * <p>The two wallet UPDATEs always run in ascending wallet-id order rather
 * than caller-chosen from/to order. Postgres holds UPDATE row locks until
 * COMMIT, so without a fixed order, A->B and B->A racing on the same pair
 * of wallets deadlock. Ascending-id order makes that cycle impossible.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_DECLINED = "DECLINED_INSUFFICIENT_FUNDS";

    // DO UPDATE instead of DO NOTHING: a losing concurrent insert then
    // blocks on the winner's row lock and reads back its committed result,
    // instead of needing a separate retry-with-backoff loop.
    private static final String CLAIM_KEY_SQL = """
            INSERT INTO transfers (idempotency_key, request_hash, from_wallet, to_wallet, amount_paise, status)
            VALUES (:key, :hash, :fromWallet, :toWallet, :amount, 'PENDING')
            ON CONFLICT (idempotency_key) DO UPDATE SET idempotency_key = EXCLUDED.idempotency_key
            RETURNING id, request_hash, status, amount_paise
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final WalletService walletService;
    private final DomainMetrics metrics;

    private static final String GET_BY_ID_SQL = """
            SELECT t.id, t.status, t.amount_paise, wf.user_id AS from_user_id, wt.user_id AS to_user_id
            FROM transfers t
            JOIN wallets wf ON wf.id = t.from_wallet
            JOIN wallets wt ON wt.id = t.to_wallet
            WHERE t.id = :id
            """;

    public TransferService(NamedParameterJdbcTemplate jdbc, WalletService walletService, DomainMetrics metrics) {
        this.jdbc = jdbc;
        this.walletService = walletService;
        this.metrics = metrics;
    }

    public TransferResponse getById(long id) {
        try {
            Map<String, Object> row = jdbc.queryForMap(GET_BY_ID_SQL, new MapSqlParameterSource("id", id));
            return new TransferResponse(
                    id,
                    (String) row.get("status"),
                    (String) row.get("from_user_id"),
                    (String) row.get("to_user_id"),
                    ((Number) row.get("amount_paise")).longValue(),
                    false);
        } catch (EmptyResultDataAccessException e) {
            throw new TransferNotFoundException("transfer not found for id=" + id);
        }
    }

    @Transactional
    public TransferResponse transfer(TransferRequest req) {
        // Compare resolved wallet ids, not the raw request strings, so a
        // self-transfer expressed as a user_id on one side and that same
        // user's wallet id on the other is still caught.
        WalletResponse from = walletService.resolveWallet(req.fromUserId());
        WalletResponse to = walletService.resolveWallet(req.toUserId());
        if (from.id() == to.id()) {
            throw new InvalidTransferException("from and to must be different wallets");
        }

        long fromWalletId = from.id();
        long toWalletId = to.id();
        String hash = requestHash(fromWalletId, toWalletId, req.amountPaise());

        Map<String, Object> claimed = jdbc.queryForMap(CLAIM_KEY_SQL, new MapSqlParameterSource()
                .addValue("key", req.idempotencyKey())
                .addValue("hash", hash)
                .addValue("fromWallet", fromWalletId)
                .addValue("toWallet", toWalletId)
                .addValue("amount", req.amountPaise()));

        long transferId = ((Number) claimed.get("id")).longValue();
        String storedHash = (String) claimed.get("request_hash");
        String storedStatus = (String) claimed.get("status");
        long storedAmount = ((Number) claimed.get("amount_paise")).longValue();

        if (!STATUS_PENDING.equals(storedStatus)) {
            if (!storedHash.equals(hash)) {
                throw new IdempotencyConflictException("idempotency_key already used with a different request");
            }
            metrics.idempotentReplay();
            log.info("transfer.idempotent_replay transfer_id={} idempotency_key={}", transferId, req.idempotencyKey());
            return new TransferResponse(transferId, storedStatus, from.userId(), to.userId(), storedAmount, true);
        }

        log.info("transfer.created transfer_id={} idempotency_key={} from_wallet={} to_wallet={} amount_paise={}",
                transferId, req.idempotencyKey(), fromWalletId, toWalletId, req.amountPaise());

        long amount = req.amountPaise();
        long firstWalletId = Math.min(fromWalletId, toWalletId);
        long secondWalletId = Math.max(fromWalletId, toWalletId);
        boolean fromIsFirst = fromWalletId == firstWalletId;
        long firstDelta = fromIsFirst ? -amount : amount;
        long secondDelta = fromIsFirst ? amount : -amount;

        Long firstBalance = applyDelta(firstWalletId, firstDelta);
        if (firstBalance == null) {
            // Only a debit can fail this check, and nothing else has
            // touched either wallet yet.
            return decline(transferId, req, from, to);
        }
        log.info("transfer.{} transfer_id={} wallet={} balance_after={}",
                fromIsFirst ? "debited" : "credited", transferId, firstWalletId, firstBalance);

        Long secondBalance = applyDelta(secondWalletId, secondDelta);
        if (secondBalance == null) {
            // Debit was second and failed; reverse the credit already
            // applied to firstWalletId, still inside the same transaction.
            applyDelta(firstWalletId, -firstDelta);
            return decline(transferId, req, from, to);
        }
        log.info("transfer.{} transfer_id={} wallet={} balance_after={}",
                fromIsFirst ? "credited" : "debited", transferId, secondWalletId, secondBalance);

        jdbc.update("""
                INSERT INTO ledger_entries (transfer_id, wallet_id, delta_paise)
                VALUES (:transferId, :fromWallet, :debit), (:transferId, :toWallet, :credit)
                """, new MapSqlParameterSource()
                .addValue("transferId", transferId)
                .addValue("fromWallet", fromWalletId)
                .addValue("debit", -req.amountPaise())
                .addValue("toWallet", toWalletId)
                .addValue("credit", req.amountPaise()));

        jdbc.update("UPDATE transfers SET status = :status WHERE id = :id",
                new MapSqlParameterSource("status", STATUS_COMPLETED).addValue("id", transferId));

        metrics.transferCreated();
        return new TransferResponse(transferId, STATUS_COMPLETED, from.userId(), to.userId(), req.amountPaise(), false);
    }

    // Covers both debit and credit: a negative delta enforces the
    // non-negative-balance invariant, a positive one always passes. Returns
    // null instead of throwing so the caller can tell "insufficient funds"
    // apart from a real error.
    private Long applyDelta(long walletId, long delta) {
        try {
            return jdbc.queryForObject(
                    "UPDATE wallets SET balance = balance + :delta WHERE id = :walletId AND balance + :delta >= 0 RETURNING balance",
                    new MapSqlParameterSource("delta", delta).addValue("walletId", walletId),
                    Long.class);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private TransferResponse decline(long transferId, TransferRequest req, WalletResponse from, WalletResponse to) {
        jdbc.update("UPDATE transfers SET status = :status WHERE id = :id",
                new MapSqlParameterSource("status", STATUS_DECLINED).addValue("id", transferId));

        metrics.transferDeclinedInsufficientFunds();
        log.info("transfer.declined_insufficient_funds transfer_id={}", transferId);

        return new TransferResponse(transferId, STATUS_DECLINED, from.userId(), to.userId(), req.amountPaise(), false);
    }

    private static String requestHash(long fromWallet, long toWallet, long amountPaise) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest((fromWallet + "|" + toWallet + "|" + amountPaise).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
