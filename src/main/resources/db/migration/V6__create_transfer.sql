CREATE TABLE transfer
(
    id                     UUID                     NOT NULL,
    reference              VARCHAR(50)              NOT NULL,
    transfer_type          VARCHAR(20)              NOT NULL,
    status                 VARCHAR(20)              NOT NULL,
    customer_id            UUID                     NOT NULL,
    source_account_id      UUID                     NOT NULL,
    destination_account_id UUID,
    amount_minor           BIGINT                   NOT NULL,
    fee_minor              BIGINT                   NOT NULL DEFAULT 0,
    currency               VARCHAR(3)               NOT NULL,
    narration              VARCHAR(140),
    failure_reason         VARCHAR(255),
    usage_date             DATE                     NOT NULL,
    completed_at           TIMESTAMP WITH TIME ZONE,
    version                BIGINT                   NOT NULL DEFAULT 0,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_transfer PRIMARY KEY (id),
    CONSTRAINT uq_transfer_reference UNIQUE (reference),
    CONSTRAINT fk_transfer_customer FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT fk_transfer_source FOREIGN KEY (source_account_id) REFERENCES account (id),
    CONSTRAINT fk_transfer_destination FOREIGN KEY (destination_account_id) REFERENCES account (id),
    CONSTRAINT ck_transfer_type CHECK (transfer_type IN ('INTERNAL', 'EXTERNAL')),
    CONSTRAINT ck_transfer_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED', 'REVERSED')),
    CONSTRAINT ck_transfer_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_transfer_fee_not_negative CHECK (fee_minor >= 0),
    CONSTRAINT ck_transfer_internal_has_destination CHECK (transfer_type <> 'INTERNAL' OR destination_account_id IS NOT NULL)
);

CREATE INDEX idx_transfer_source ON transfer (source_account_id, created_at);
CREATE INDEX idx_transfer_destination ON transfer (destination_account_id, created_at);
CREATE INDEX idx_transfer_status ON transfer (status);

-- Remembers which transfer a client's Idempotency-Key produced, so a retry gets the same answer.
CREATE TABLE idempotency_key
(
    id           UUID                     NOT NULL,
    scope        VARCHAR(100)             NOT NULL,
    idem_key     VARCHAR(100)             NOT NULL,
    request_hash VARCHAR(64)              NOT NULL,
    transfer_id  UUID,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_idempotency_key PRIMARY KEY (id),
    -- the unique constraint is what makes this safe when two identical requests arrive at the same moment
    CONSTRAINT uq_idempotency_key_scope_key UNIQUE (scope, idem_key),
    CONSTRAINT fk_idempotency_key_transfer FOREIGN KEY (transfer_id) REFERENCES transfer (id)
);
