-- Every webhook a payment provider sends us (Step 12).
-- The unique (provider, event_id) pair is how a repeated delivery is recognised: providers retry, so the same event can arrive
-- many times, and it must only ever be applied once.
-- The raw body is deliberately NOT stored (it holds the recipient's name and account details); only its SHA-256 fingerprint is.
CREATE TABLE webhook_event
(
    id             UUID                     NOT NULL,
    provider       VARCHAR(20)              NOT NULL,
    event_id       VARCHAR(200)             NOT NULL,
    event_type     VARCHAR(100)             NOT NULL,
    reference      VARCHAR(100),
    outcome        VARCHAR(20),
    amount_minor   BIGINT,
    status         VARCHAR(30)              NOT NULL,
    detail         VARCHAR(500),
    payload_sha256 VARCHAR(64)              NOT NULL,
    attempts       INTEGER                  NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_webhook_event PRIMARY KEY (id),
    CONSTRAINT uq_webhook_event UNIQUE (provider, event_id),
    CONSTRAINT ck_webhook_event_status CHECK (status IN
        ('RECEIVED', 'APPLIED', 'IGNORED', 'UNMATCHED', 'AMOUNT_MISMATCH', 'MANUAL_REVIEW', 'FAILED'))
);

CREATE INDEX idx_webhook_event_reference ON webhook_event (reference);
