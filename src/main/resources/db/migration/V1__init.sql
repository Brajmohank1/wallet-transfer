CREATE TABLE wallets (
  id          BIGSERIAL PRIMARY KEY,
  user_id     TEXT NOT NULL UNIQUE,           -- makes get-or-create race-free
  balance     BIGINT NOT NULL DEFAULT 0 CHECK (balance >= 0),  -- paise; belt & braces
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transfers (
  id               BIGSERIAL PRIMARY KEY,
  idempotency_key  TEXT NOT NULL UNIQUE,      -- the exactly-once guarantee
  request_hash     TEXT NOT NULL,             -- sha256 of from|to|amount -> detects 409
  from_wallet      BIGINT NOT NULL REFERENCES wallets(id),
  to_wallet        BIGINT NOT NULL REFERENCES wallets(id),
  amount_paise     BIGINT NOT NULL CHECK (amount_paise > 0),
  status           TEXT NOT NULL,             -- PENDING | COMPLETED | DECLINED_INSUFFICIENT_FUNDS
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
  id           BIGSERIAL PRIMARY KEY,
  transfer_id  BIGINT NOT NULL REFERENCES transfers(id),
  wallet_id    BIGINT NOT NULL REFERENCES wallets(id),
  delta_paise  BIGINT NOT NULL,               -- negative = debit, positive = credit
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ledger_entries_wallet_id_idx ON ledger_entries (wallet_id);
CREATE INDEX ledger_entries_transfer_id_idx ON ledger_entries (transfer_id);
