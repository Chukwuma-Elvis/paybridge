-- System-initiated payouts (Step 22, ADR-0006): naira sent to a recipient at another bank when the money comes from one of the bank's
-- own accounts (the remittance clearing account) rather than from a customer. It follows the same shape as an external transfer:
-- hold in suspense, send through the provider, then settle or release.
CREATE TABLE system_payout
(
    id                 UUID                     NOT NULL,
    reference          VARCHAR(40)              NOT NULL,
    source_account_id  UUID                     NOT NULL,
    amount_minor       BIGINT                   NOT NULL,
    currency           VARCHAR(3)               NOT NULL,
    bank_code          VARCHAR(10)              NOT NULL,
    account_number     VARCHAR(10)              NOT NULL,
    account_name       VARCHAR(100)             NOT NULL,
    narration          VARCHAR(100),
    status             VARCHAR(12)              NOT NULL,
    dispatch_state     VARCHAR(8)               NOT NULL,
    attempts           INTEGER                  NOT NULL DEFAULT 0,
    next_attempt_at    TIMESTAMP WITH TIME ZONE,
    provider_reference VARCHAR(100),
    failure_reason     VARCHAR(255),
    last_error         VARCHAR(255),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_system_payout PRIMARY KEY (id),
    CONSTRAINT uq_system_payout_reference UNIQUE (reference),
    CONSTRAINT fk_system_payout_source FOREIGN KEY (source_account_id) REFERENCES account (id),
    CONSTRAINT ck_system_payout_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_system_payout_dispatch CHECK (dispatch_state IN ('PENDING', 'DONE')),
    CONSTRAINT ck_system_payout_amount CHECK (amount_minor > 0)
);

CREATE INDEX idx_system_payout_due ON system_payout (dispatch_state, next_attempt_at);
CREATE INDEX idx_system_payout_status ON system_payout (status, updated_at);
