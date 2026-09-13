package com.brajmohan.wallettransfer.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.brajmohan.wallettransfer.dto.WalletResponse;
import com.brajmohan.wallettransfer.exception.WalletNotFoundException;
import com.brajmohan.wallettransfer.metrics.DomainMetrics;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    // Two statements, one transaction. A single CTE (INSERT ... ON CONFLICT
    // RETURNING, unioned with a fallback SELECT) looked cleaner but shares
    // one MVCC snapshot across both halves, so the fallback SELECT could
    // still miss a concurrent winner's just-committed row. Separate
    // statements each get a fresh snapshot under READ COMMITTED.
    private static final String GET_OR_CREATE_INSERT_SQL =
            "INSERT INTO wallets (user_id) VALUES (:userId) ON CONFLICT (user_id) DO NOTHING";
    private static final String GET_OR_CREATE_SELECT_SQL =
            "SELECT id, balance FROM wallets WHERE user_id = :userId";

    private final NamedParameterJdbcTemplate jdbc;
    private final DomainMetrics metrics;

    public WalletService(NamedParameterJdbcTemplate jdbc, DomainMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Transactional
    public WalletResponse getOrCreate(String userId) {
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
        int inserted = jdbc.update(GET_OR_CREATE_INSERT_SQL, params);

        Map<String, Object> row = jdbc.queryForMap(GET_OR_CREATE_SELECT_SQL, params);
        long id = ((Number) row.get("id")).longValue();
        long balance = ((Number) row.get("balance")).longValue();

        if (inserted > 0) {
            metrics.walletCreated();
            log.info("wallet.created user_id={} wallet_id={}", userId, id);
        }
        return new WalletResponse(id, userId, balance);
    }

    public WalletResponse getByUserId(String userId) {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT id, balance FROM wallets WHERE user_id = :userId",
                    new MapSqlParameterSource("userId", userId));
            return new WalletResponse(((Number) row.get("id")).longValue(), userId, ((Number) row.get("balance")).longValue());
        } catch (EmptyResultDataAccessException e) {
            throw new WalletNotFoundException("wallet not found for user_id=" + userId);
        }
    }

    public WalletResponse getById(long id) {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT id, user_id, balance FROM wallets WHERE id = :id",
                    new MapSqlParameterSource("id", id));
            return new WalletResponse(id, (String) row.get("user_id"), ((Number) row.get("balance")).longValue());
        } catch (EmptyResultDataAccessException e) {
            throw new WalletNotFoundException("wallet not found for id=" + id);
        }
    }

    // Accepts either a wallet id or a user_id for a transfer's from/to --
    // the spec doesn't say which one identifies a party. Not used by
    // GET /wallets/{id}, which is unambiguously wallet-id-keyed.
    public WalletResponse resolveWallet(String idOrUserId) {
        try {
            return getById(Long.parseLong(idOrUserId));
        } catch (NumberFormatException notNumeric) {
            return getByUserId(idOrUserId);
        }
    }

    // Test/seed-only: no ledger entry or idempotency key, just seeds a
    // balance for the burst script. Real money-in would come through a
    // payment provider webhook, out of scope here.
    public WalletResponse deposit(String userId, long amountPaise) {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "UPDATE wallets SET balance = balance + :amount WHERE user_id = :userId RETURNING id, balance",
                    new MapSqlParameterSource("amount", amountPaise).addValue("userId", userId));
            long id = ((Number) row.get("id")).longValue();
            long balance = ((Number) row.get("balance")).longValue();
            log.info("wallet.deposited user_id={} wallet_id={} amount_paise={}", userId, id, amountPaise);
            return new WalletResponse(id, userId, balance);
        } catch (EmptyResultDataAccessException e) {
            throw new WalletNotFoundException("wallet not found for user_id=" + userId);
        }
    }
}
