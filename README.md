# Wallet & P2P Transfer

A wallet and peer-to-peer transfer service: race-free wallet get-or-create, exactly-once
transfers under a reused idempotency key, and a live burst-test harness to prove it.

Stack: Java 17 + Spring Boot (Spring MVC + Spring JDBC + Flyway + Actuator/Micrometer) +
Postgres 16. See [`docs/writeup.md`](docs/writeup.md) for the design rationale, the
alternatives considered and rejected, and an honest account of what's been verified so far.

**Live deployment:** [`https://wallet-transfer-8e1o.onrender.com`](https://wallet-transfer-8e1o.onrender.com)
(Render, free web service + free managed Postgres) — try it now, no setup needed, with
`Authorization: Bearer demo-token` (see Auth below).

**First request may be slow.** Render's free tier sleeps the service after 15 minutes of no
traffic; waking it up takes up to ~60 seconds. If the very first request times out or hangs,
that's the platform waking up, not the app — retry after a few seconds, or hit `/healthz` once
first and wait for `200` before running anything else. Every request after that is normal speed.

**Logs, live:** a screen recording of the structured domain-event logs streaming during a real
burst run — [video](https://drive.google.com/file/d/1KSBWIhBi6r4FvDxUEMYsN--p7au8_9Jj/view?usp=sharing).
A static transcript of the same kind of output is also committed at
[`docs/live-burst-log-sample.txt`](docs/live-burst-log-sample.txt).

## Run it locally

```bash
docker compose up --build
```

This starts Postgres (healthcheck-gated) and the app, running Flyway migrations on boot.

### Auth

Every endpoint except `/healthz` and `/metrics` requires `Authorization: Bearer <token>`.
Locally this defaults to a built-in demo token, no setup needed: `Bearer demo-token`. In
deployment, set `AUTH_TOKENS` to a comma-separated `token:user_id` list, e.g.
`AUTH_TOKENS=abc123:alice,def456:bob`. The token identifies the caller (available to the
request as an attribute); it does not itself restrict which wallets a caller can act on.

**`Bearer demo-token` also works against the live deployment**
(`https://wallet-transfer-8e1o.onrender.com`) — same token, same behavior as local, so there's
nothing extra to request or configure to try it:

```bash
curl -H "Authorization: Bearer demo-token" -X POST https://wallet-transfer-8e1o.onrender.com/wallets \
  -H "Content-Type: application/json" -d '{"user_id":"reviewer"}'
```

### Endpoints

- `GET  /healthz` — liveness/readiness, no auth required
- `GET  /metrics` — Prometheus exposition format, no auth required
- `POST /wallets` — get-or-create, body `{"user_id": "alice"}`
- `GET  /wallets/{id}` — the numeric wallet id `POST /wallets` returned (not the user_id); `400`
  on a non-numeric id, `404` if no wallet has that id
- `POST /wallets/{userId}/deposit` — test/seed-only, body `{"amount_paise": 10000}` (see
  [`docs/writeup.md`](docs/writeup.md) for why this exists outside the graded correctness core)
- `POST /transfers` — body:
  ```json
  {
    "idempotency_key": "any client-generated string",
    "from_user_id": "alice",
    "to_user_id": "bob",
    "amount_paise": 500
  }
  ```
  `from_user_id`/`to_user_id` each accept **either** the user_id passed to `POST /wallets` **or**
  the numeric wallet id it returned — the source brief specifies a transfer body carries `from`/
  `to` but doesn't say which of the two a transfer identifies a party by, so both are accepted
  rather than picking one and silently rejecting the other (see `docs/writeup.md`'s "AI: directed
  vs decided" section). Returns `200` with `status: "COMPLETED"`, `422` with `status:
  "DECLINED_INSUFFICIENT_FUNDS"`, or `409` if `idempotency_key` is reused with a request that
  hashes differently from the one originally stored under it.
- `GET  /transfers/{id}` — the recorded outcome of a past transfer (`200` regardless of
  whether that outcome was `COMPLETED` or `DECLINED_INSUFFICIENT_FUNDS`; `404` only if the id
  doesn't exist)

Example, against the default local demo token:

```bash
curl -H 'Authorization: Bearer demo-token' -X POST localhost:8080/wallets \
  -H 'Content-Type: application/json' -d '{"user_id":"alice"}'
```

## Run the burst test

```bash
./burst.sh http://localhost:8080
# or, against a deployed instance with a real token:
./burst.sh https://<deployed-host> <bearer-token>
```

`burst/Burst.java` is a self-contained Java program (JEP 330 single-file source launch — no
build step) run by `burst.sh`. It fires three genuinely concurrent probes — a wallet
get-or-create race, an idempotency storm, and conservation-under-contention across five
wallets — and prints a `PASS`/`FAIL` line. Run it against the deployed URL, not localhost:
free-tier latency surfaces races a local Postgres hides.

## Build without Docker

Requires JDK 17+ and a local Postgres reachable at `jdbc:postgresql://localhost:5432/wallet`
(user/password `wallet`/`wallet` by default). Override with either `DATABASE_URL` (a
`postgres://user:pass@host:port/db` URI, translated at startup — see
`WalletTransferApplication.applyDatabaseUrlIfPresent`) or Spring's own
`SPRING_DATASOURCE_URL`/`SPRING_DATASOURCE_USERNAME`/`SPRING_DATASOURCE_PASSWORD`:

```bash
./mvnw spring-boot:run
```

## Layout

```
src/main/java/.../wallettransfer/
  web/        REST controllers + the exception -> HTTP status mapping
  service/    WalletService, TransferService — the correctness core lives here
  dto/        request/response records
  filter/     correlation-id propagation, bearer-token auth gate
  config/     AUTH_TOKENS parsing
  metrics/    domain Prometheus counters
src/main/resources/
  application.yml
  db/migration/V1__init.sql   Flyway migration
burst/Burst.java   concurrency/load test harness
docs/writeup.md    design write-up
```
