-- Remittances (Steps 19 to 23): USD collected abroad by a corridor partner, converted, and paid out in naira in Nigeria.
-- The status column is the state machine; every change of it is also written to remittance_event, which can never be edited.
CREATE TABLE remittance
(
    id                        UUID                     NOT NULL,
    reference                 VARCHAR(40)              NOT NULL,
    client_name               VARCHAR(100)             NOT NULL,
    idempotency_key           VARCHAR(100)             NOT NULL,
    request_hash              VARCHAR(64)              NOT NULL,
    partner_code              VARCHAR(16)              NOT NULL,
    partner_reference         VARCHAR(100)             NOT NULL,
    corridor                  VARCHAR(20)              NOT NULL,
    quote_id                  UUID                     NOT NULL,
    sell_minor                BIGINT                   NOT NULL,
    fee_minor                 BIGINT                   NOT NULL,
    buy_minor                 BIGINT                   NOT NULL,
    -- who: the minimum needed to screen and pay (no identity-document number is stored at all)
    sender_key                VARCHAR(120)             NOT NULL,
    sender_name               VARCHAR(200)             NOT NULL,
    sender_country            VARCHAR(2),
    sender_birth              DATE,
    recipient_key             VARCHAR(120)             NOT NULL,
    recipient_name            VARCHAR(200)             NOT NULL,
    recipient_country         VARCHAR(2),
    recipient_type            VARCHAR(10)              NOT NULL,
    recipient_bank_code       VARCHAR(10),
    recipient_account_number  VARCHAR(10)              NOT NULL,
    status                    VARCHAR(20)              NOT NULL,
    status_reason             VARCHAR(255),
    sender_case_id            UUID,
    recipient_case_id         UUID,
    velocity_case_id          UUID,
    funding_case_id           UUID,
    usd_usage_date            DATE,
    limits_reserved           BOOLEAN                  NOT NULL DEFAULT FALSE,
    funding_expires_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    funded_at                 TIMESTAMP WITH TIME ZONE,
    paid_at                   TIMESTAMP WITH TIME ZONE,
    payout_reference          VARCHAR(40),
    attempts                  INTEGER                  NOT NULL DEFAULT 0,
    next_attempt_at           TIMESTAMP WITH TIME ZONE,
    last_error                VARCHAR(255),
    version                   BIGINT                   NOT NULL DEFAULT 0,
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_remittance PRIMARY KEY (id),
    CONSTRAINT uq_remittance_reference UNIQUE (reference),
    -- a retried create is recognised by this pair
    CONSTRAINT uq_remittance_idempotency UNIQUE (client_name, idempotency_key),
    -- the partner's own reference for the same payment can only be accepted once
    CONSTRAINT uq_remittance_partner_ref UNIQUE (partner_code, partner_reference),
    -- one quote pays for exactly one remittance
    CONSTRAINT uq_remittance_quote UNIQUE (quote_id),
    CONSTRAINT ck_remittance_status CHECK (status IN ('CREATED', 'SCREENING', 'UNDER_REVIEW', 'AWAITING_FUNDING', 'FUNDED',
        'CONVERTED', 'PAYOUT_PENDING', 'PAID', 'FAILED', 'REFUND_PENDING', 'REFUNDED', 'CANCELLED')),
    CONSTRAINT ck_remittance_recipient_type CHECK (recipient_type IN ('PAYBRIDGE', 'BANK')),
    CONSTRAINT ck_remittance_bank CHECK (recipient_type <> 'BANK' OR recipient_bank_code IS NOT NULL),
    CONSTRAINT ck_remittance_amounts CHECK (sell_minor > 0 AND fee_minor >= 0 AND buy_minor > 0 AND fee_minor < sell_minor)
);

CREATE INDEX idx_remittance_status ON remittance (status, next_attempt_at);
CREATE INDEX idx_remittance_sender ON remittance (sender_key, created_at DESC);

-- Every move of a remittance from one status to the next, in order, forever.
CREATE TABLE remittance_event
(
    id            UUID                     NOT NULL,
    remittance_id UUID                     NOT NULL,
    from_status   VARCHAR(20),
    to_status     VARCHAR(20)              NOT NULL,
    note          VARCHAR(255),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_remittance_event PRIMARY KEY (id),
    CONSTRAINT fk_remittance_event FOREIGN KEY (remittance_id) REFERENCES remittance (id)
);

CREATE INDEX idx_remittance_event_remittance ON remittance_event (remittance_id, created_at);

CREATE FUNCTION remittance_event_forbid_change() RETURNS trigger AS
$$
BEGIN
    RAISE EXCEPTION 'the remittance history is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME USING ERRCODE = '23000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_remittance_event_append_only
    BEFORE UPDATE OR DELETE
    ON remittance_event
    FOR EACH ROW
EXECUTE FUNCTION remittance_event_forbid_change();

-- The idempotency claim: the first request with a (client, key) pair inserts this row, a repeat finds it. The insert is
-- ON CONFLICT DO NOTHING, so when two identical requests arrive together exactly one of them owns the key.
CREATE TABLE remittance_claim
(
    client_name     VARCHAR(100)             NOT NULL,
    idempotency_key VARCHAR(100)             NOT NULL,
    request_hash    VARCHAR(64)              NOT NULL,
    remittance_id   UUID                     NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_remittance_claim PRIMARY KEY (client_name, idempotency_key)
);
