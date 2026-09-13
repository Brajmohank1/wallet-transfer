# Wallet & P2P Transfer — Write-up

## Data model

Three tables, `BIGINT` paise throughout — no `NUMERIC`, no `FLOAT`, no rupees-as-decimal
anywhere, not in the DB, not in the JSON, not in the Java types (`long` end to end; Jackson
never deserializes an amount into a floating-point type).

- **`wallets`** — one row per user. `user_id` is `UNIQUE`, which is what makes get-or-create
  race-free. `balance CHECK (balance >= 0)` is a second line of defence: the conditional debit
  below should mean it never fires, and if it ever does, that's a bug worth knowing about loudly.
- **`transfers`** — one row per transfer attempt. `idempotency_key UNIQUE` is the exactly-once
  guarantee. `request_hash` (sha256 of `from_wallet|to_wallet|amount_paise`) distinguishes a
  genuine retry from a different request that happens to reuse a key.
- **`ledger_entries`** — two rows per completed transfer (`-amount` debited, `+amount` credited),
  an append-only audit trail independent of `wallets.balance`.

## Simplest-correct mechanism

The transfer is one transaction, in this order:

1. Resolve `from`/`to` wallet ids (plain reads, order doesn't matter).
2. Claim the idempotency key — `INSERT ... ON CONFLICT (idempotency_key) DO UPDATE SET
   idempotency_key = EXCLUDED.idempotency_key RETURNING id, request_hash, status, amount_paise`.
   `DO UPDATE` instead of `DO NOTHING` matters: a losing concurrent insert blocks on the winner's
   row lock and reads back its committed result, so no retry-with-backoff loop is needed.
3. If the returned row's status isn't `PENDING`, someone already resolved this key: compare
   `request_hash` — match → return the stored result (idempotent replay), differ → `409`.
4. Otherwise apply both balance changes with the same conditional statement shape —
   `UPDATE wallets SET balance = balance + :delta WHERE id = :id AND balance + :delta >= 0
   RETURNING balance` — a debit passes a negative delta (enforces the invariant), a credit a
   positive one (always passes). **Applied in ascending wallet-id order, never caller-chosen
   from/to order.** Postgres holds `UPDATE` row locks until `COMMIT`, so without a fixed order,
   concurrent `A→B` and `B→A` transfers on the same pair of wallets deadlock — a real
   `ERROR: deadlock detected` the burst test caught under load. Ascending-id order makes the
   cycle structurally impossible. If the debit side's check fails: if applied first, nothing
   else has happened — decline immediately; if applied second, reverse the credit already
   applied to the other wallet with one more conditional `UPDATE`, still in the same
   transaction. Either way: zero net effect, mark `DECLINED_INSUFFICIENT_FUNDS`, return `422`.
5. Both succeeded: write two ledger rows, mark `COMPLETED`, commit.

**Heavier alternatives rejected:**

- `SELECT ... FOR UPDATE` with a sorted lock order — correct, but two explicit locks and
  discipline this shape gets for free from the conditional `UPDATE` plus fixed ordering.
- `SERIALIZABLE` isolation — correct, but needs retry-on-serialization-failure plumbing and
  degrades under contention; would be cargo-culting here, not a considered choice.
- Read balance into application code, subtract, write back — the lost-update bug: concurrent
  debits both read the pre-decrement balance and both "succeed," overdrawing the wallet.

## Where idempotency lives

In the *same* transaction as the ledger movement — the key claim and the balance updates never
commit separately. Checking the key in an earlier, separate transaction is the TOCTOU bug: two
concurrent retries would both observe "no key yet," both pass, and both debit. Postgres's own
`ON CONFLICT` handling closes the remaining race, per the `DO UPDATE ... RETURNING` trick above.

## Consistency vs availability

Chosen: **CP**. A single primary Postgres instance is the source of truth for every wallet
balance; transfers are unavailable during failover rather than silently accepted by a replica
that might diverge. Eventual consistency was rejected for this domain: a wallet that accepts
writes while partitioned from the primary can debit and credit against a stale balance it can't
reconcile — a way to create money that didn't exist. For a ledger, correctness during a
partition matters more than uptime during one.

## AI: directed vs decided

Built with Claude Code from an initial implementation plan, with hands-on direction throughout
rather than a single hands-off prompt.

- **Directed:** the original plan specified Go; the stack was switched to Java 17 + Spring Boot
  partway through, by explicit instruction, accepting the trade-off that a JVM's cold start is
  slower than a static Go binary's (mitigated with a slim `-jre` image, a capped heap, and a
  generous Docker `HEALTHCHECK --start-period`; measured at ~22.6s process boot / ~35s
  end-to-end on a 512MB instance, comfortably inside a 45s window). Later, deploying to a free
  host was directed to specifically satisfy "a free **managed** Postgres" — the first deploy
  (Fly.io) used an unmanaged single-node Postgres to stay free, which didn't actually meet that
  requirement, so the deployment was migrated to Render, whose free Postgres is genuinely managed.
- **Decided within that direction:** `NamedParameterJdbcTemplate` with hand-written SQL instead
  of JPA/Hibernate, specifically so the exact statement shapes the correctness argument depends
  on (`ON CONFLICT ... DO UPDATE ... RETURNING`, the conditional debit) aren't at an ORM's mercy;
  Flyway for migrations; structured JSON logging via Spring's built-in support plus an MDC-based
  correlation-id filter rather than a separate logging library.
- **Caught by testing, not designed for up front:** an early version of the deadlock reasoning
  above assumed a conditional `UPDATE` alone was enough with no lock ordering needed — wrong, and
  the burst script proved it wrong within minutes under concurrent `A→B`/`B→A` load. Two more
  bugs surfaced the same way once actually deployed and burst-tested live rather than trusting a
  local pass: a dropped `sslmode` query parameter on the platform's `DATABASE_URL`, and (after
  the Render migration) the free web service crashing under the heaviest probe — traced to
  Tomcat's default 200-thread pool reserving too much native memory on a constrained instance,
  fixed by capping it at 50 and matching the DB connection pool down to 10. Each was found by
  actually re-running the required burst test against the real deployment, not assumed fixed
  because the local suite passed.

## Free-tier cost

**₹0.** Deployed on Render: a free web service (Docker build from this repo's `Dockerfile`) plus
Render's free managed Postgres 16, at `https://wallet-transfer-8e1o.onrender.com`.

Two disclosed, real constraints of the free tier rather than silently-hoped-around ones:

- Render's free Postgres expires 30 days after creation (14-day grace period to upgrade before
  deletion) — fine for a project reviewed within that window, not something to build on long-term.
- The free web service sleeps after 15 minutes idle and takes up to ~60s to wake on the next
  request. Since correctness here gets reproduced directly against the live URL, an automated
  check with a short timeout could see a cold first request fail for a platform reason, not a
  code one — noted in the README so a first slow response isn't mistaken for a bug.

## Known gaps

- Logs aren't publicly streamable by URL on either free host tried (Fly or Render) — resolved
  instead with a screen recording of them streaming during a live burst run (linked in the
  README) plus a static transcript at [`docs/live-burst-log-sample.txt`](live-burst-log-sample.txt).
- `POST /transfers`'s `from_user_id`/`to_user_id` accept either a user_id or the numeric wallet
  id `POST /wallets` returns — the brief doesn't specify which one a transfer identifies a party
  by, so both are accepted rather than picking one and silently rejecting the other.
- `POST /wallets/{userId}/deposit` is a test/seed-only helper outside the graded correctness
  core, used by the burst script to set up known balances; real money-in would arrive through a
  payment provider webhook with its own exactly-once handling.
