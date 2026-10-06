-- A saved payee at another bank (what Paystack calls a "transfer recipient").
CREATE TABLE beneficiary
(
    id                      UUID                     NOT NULL,
    customer_id             UUID                     NOT NULL,
    bank_code               VARCHAR(10)              NOT NULL,
    account_number          VARCHAR(10)              NOT NULL,
    account_name            VARCHAR(200)             NOT NULL,
    provider_recipient_code VARCHAR(100),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_beneficiary PRIMARY KEY (id),
    CONSTRAINT fk_beneficiary_customer FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT uq_beneficiary_customer_bank_account UNIQUE (customer_id, bank_code, account_number)
);

ALTER TABLE transfer
    ADD COLUMN beneficiary_id     UUID,
    ADD COLUMN provider_reference VARCHAR(100);

ALTER TABLE transfer
    ADD CONSTRAINT fk_transfer_beneficiary FOREIGN KEY (beneficiary_id) REFERENCES beneficiary (id),
    ADD CONSTRAINT ck_transfer_external_has_beneficiary CHECK (transfer_type <> 'EXTERNAL' OR beneficiary_id IS NOT NULL);

-- The outbox: "this transfer still has to be sent to the provider".
-- It is written in the SAME transaction as the transfer, so a transfer can never be saved without its outbox row.
CREATE TABLE transfer_outbox
(
    id              UUID                     NOT NULL,
    transfer_id     UUID                     NOT NULL,
    status          VARCHAR(10)              NOT NULL,
    attempts        INTEGER                  NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error      VARCHAR(500),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_transfer_outbox PRIMARY KEY (id),
    CONSTRAINT uq_transfer_outbox_transfer UNIQUE (transfer_id),
    CONSTRAINT fk_transfer_outbox_transfer FOREIGN KEY (transfer_id) REFERENCES transfer (id),
    CONSTRAINT ck_transfer_outbox_status CHECK (status IN ('PENDING', 'DONE'))
);

CREATE INDEX idx_transfer_outbox_due ON transfer_outbox (status, next_attempt_at);
