-- Balance checkpoints: stated account balances extracted from bank messages.
-- Used by Reports.reconciliation to detect ledger divergences.
CREATE TABLE IF NOT EXISTS balance_checkpoint (
    id           IDENTITY PRIMARY KEY,
    account_last4 VARCHAR(4)     NOT NULL,
    occurred_at   VARCHAR(40)    NOT NULL,
    balance       DECIMAL(14, 2) NOT NULL,
    source_message_id VARCHAR(60) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_checkpoint_account ON balance_checkpoint (account_last4);
