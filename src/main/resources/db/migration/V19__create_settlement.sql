-- Settlement and reconciliation (Steps 24 to 26).
-- PayBridge and a corridor partner each keep a record of the same money movements. A batch freezes a slice of OUR record (the lines on
-- the partner's settlement account), the partner's statement is compared with it, every difference becomes a break, and the net is paid.

CREATE TABLE settlement_partner
(
    code       VARCHAR(16)              NOT NULL,
    currency   VARCHAR(3)               NOT NULL,
    account_id UUID                     NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_settlement_partner PRIMARY KEY (code),
    CONSTRAINT fk_settlement_partner_account FOREIGN KEY (account_id) REFERENCES account (id)
);

CREATE TABLE settlement_batch
(
    id              UUID                     NOT NULL,
    partner_code    VARCHAR(16)              NOT NULL,
    sequence        INTEGER                  NOT NULL,
    currency        VARCHAR(3)               NOT NULL,
    status          VARCHAR(12)              NOT NULL,
    cutoff_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    opening_minor   BIGINT                   NOT NULL,
    movements_minor BIGINT                   NOT NULL,
    closing_minor   BIGINT                   NOT NULL,
    line_count      INTEGER                  NOT NULL,
    payment_entry_id UUID,
    dispute_note    VARCHAR(500),
    closed_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    instructed_at   TIMESTAMP WITH TIME ZONE,
    confirmed_at    TIMESTAMP WITH TIME ZONE,
    reconciled_at   TIMESTAMP WITH TIME ZONE,

    CONSTRAINT pk_settlement_batch PRIMARY KEY (id),
    CONSTRAINT fk_settlement_batch_partner FOREIGN KEY (partner_code) REFERENCES settlement_partner (code),
    CONSTRAINT uq_settlement_batch_sequence UNIQUE (partner_code, sequence),
    -- closing the same cut-off twice can only ever give the one batch
    CONSTRAINT uq_settlement_batch_cutoff UNIQUE (partner_code, cutoff_at),
    CONSTRAINT ck_settlement_batch_status CHECK (status IN ('CLOSED', 'INSTRUCTED', 'CONFIRMED', 'RECONCILED', 'DISPUTED')),
    CONSTRAINT ck_settlement_batch_totals CHECK (closing_minor = opening_minor + movements_minor)
);

-- Every ledger line on a partner account belongs to exactly one batch: the unique ledger_line_id is the guarantee.
-- Lines are copies of facts that never change, so they are never edited or deleted.
CREATE TABLE settlement_batch_line
(
    id             UUID                     NOT NULL,
    batch_id       UUID                     NOT NULL,
    ledger_line_id UUID                     NOT NULL,
    entry_id       UUID                     NOT NULL,
    entry_type     VARCHAR(40)              NOT NULL,
    reference      VARCHAR(100)             NOT NULL,
    amount_minor   BIGINT                   NOT NULL,
    currency       VARCHAR(3)               NOT NULL,
    posted_at      TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_settlement_batch_line PRIMARY KEY (id),
    CONSTRAINT fk_settlement_batch_line_batch FOREIGN KEY (batch_id) REFERENCES settlement_batch (id),
    CONSTRAINT fk_settlement_batch_line_ledger FOREIGN KEY (ledger_line_id) REFERENCES ledger_line (id),
    CONSTRAINT uq_settlement_batch_line_ledger UNIQUE (ledger_line_id)
);

CREATE INDEX idx_settlement_batch_line_batch ON settlement_batch_line (batch_id);

CREATE FUNCTION settlement_batch_line_forbid_change() RETURNS trigger AS
$$
BEGIN
    RAISE EXCEPTION 'settlement batch lines are append-only: % is not allowed', TG_OP USING ERRCODE = '23000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_settlement_batch_line_append_only
    BEFORE UPDATE OR DELETE
    ON settlement_batch_line
    FOR EACH ROW
EXECUTE FUNCTION settlement_batch_line_forbid_change();

-- The partner's statement for a batch. One per batch; the fingerprint makes uploading the same file twice a no-op.
CREATE TABLE settlement_statement
(
    id          UUID                     NOT NULL,
    batch_id    UUID                     NOT NULL,
    fingerprint VARCHAR(64)              NOT NULL,
    line_count  INTEGER                  NOT NULL,
    uploaded_by VARCHAR(100)             NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_settlement_statement PRIMARY KEY (id),
    CONSTRAINT fk_settlement_statement_batch FOREIGN KEY (batch_id) REFERENCES settlement_batch (id),
    CONSTRAINT uq_settlement_statement_batch UNIQUE (batch_id)
);

CREATE TABLE settlement_statement_line
(
    id                    UUID         NOT NULL,
    statement_id          UUID         NOT NULL,
    row_number            INTEGER      NOT NULL,
    reference             VARCHAR(100),
    amount_minor          BIGINT       NOT NULL,
    currency              VARCHAR(3)   NOT NULL,
    value_date            DATE         NOT NULL,
    match_status          VARCHAR(20)  NOT NULL,
    matched_batch_line_id UUID,

    CONSTRAINT pk_settlement_statement_line PRIMARY KEY (id),
    CONSTRAINT fk_settlement_statement_line FOREIGN KEY (statement_id) REFERENCES settlement_statement (id),
    CONSTRAINT ck_settlement_statement_line_status CHECK (match_status IN ('MATCHED', 'UNMATCHED_THEIRS', 'AMOUNT_DIFFERS'))
);

CREATE INDEX idx_settlement_statement_line ON settlement_statement_line (statement_id);

-- A difference between the two records. Never silently dropped: it is resolved with a written note (and, where money differs, a
-- correcting ledger entry), and a resolved break cannot be resolved again.
CREATE TABLE settlement_break
(
    id                  UUID                     NOT NULL,
    batch_id            UUID                     NOT NULL,
    break_type          VARCHAR(20)              NOT NULL,
    batch_line_id       UUID,
    statement_line_id   UUID,
    our_amount_minor    BIGINT,
    their_amount_minor  BIGINT,
    reason              VARCHAR(255)             NOT NULL,
    status              VARCHAR(10)              NOT NULL,
    owner               VARCHAR(100),
    resolution_note     VARCHAR(500),
    resolved_by         VARCHAR(100),
    resolved_at         TIMESTAMP WITH TIME ZONE,
    adjustment_entry_id UUID,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_settlement_break PRIMARY KEY (id),
    CONSTRAINT fk_settlement_break_batch FOREIGN KEY (batch_id) REFERENCES settlement_batch (id),
    CONSTRAINT ck_settlement_break_type CHECK (break_type IN ('UNMATCHED_OURS', 'UNMATCHED_THEIRS', 'AMOUNT_DIFFERS')),
    CONSTRAINT ck_settlement_break_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_settlement_break_resolved CHECK (
        (status = 'OPEN' AND resolved_at IS NULL AND resolved_by IS NULL AND resolution_note IS NULL)
        OR
        (status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL AND length(trim(resolution_note)) > 0)
    )
);

CREATE INDEX idx_settlement_break_batch ON settlement_break (batch_id, status);
