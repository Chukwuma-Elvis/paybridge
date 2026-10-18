-- Exchange rates (Step 15). Append-only, like the ledger: a rate is never edited or deleted, because a quote, and so a customer's
-- money, may depend on exactly what the rate was. A correction or an approval is a NEW row.

CREATE TABLE fx_rate
(
    id                UUID                     NOT NULL,
    corridor          VARCHAR(20)              NOT NULL,
    mid_rate          NUMERIC(18, 6)           NOT NULL,
    source            VARCHAR(60)              NOT NULL,
    retrieved_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    status            VARCHAR(10)              NOT NULL,
    approves_rate_id  UUID,
    note              VARCHAR(200),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_fx_rate PRIMARY KEY (id),
    CONSTRAINT ck_fx_rate_status CHECK (status IN ('ACTIVE', 'HELD')),
    CONSTRAINT ck_fx_rate_positive CHECK (mid_rate > 0),
    CONSTRAINT fk_fx_rate_approves FOREIGN KEY (approves_rate_id) REFERENCES fx_rate (id),
    -- a held rate can be approved once: the approval is a new row pointing back at it
    CONSTRAINT uq_fx_rate_approves UNIQUE (approves_rate_id)
);

CREATE INDEX idx_fx_rate_latest ON fx_rate (corridor, status, created_at DESC);

-- Our margin per corridor, in basis points. Changing it adds a row; the latest row wins.
CREATE TABLE fx_spread
(
    id         UUID                     NOT NULL,
    corridor   VARCHAR(20)              NOT NULL,
    spread_bps INTEGER                  NOT NULL,
    set_by     VARCHAR(100)             NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_fx_spread PRIMARY KEY (id),
    CONSTRAINT ck_fx_spread_range CHECK (spread_bps >= 0 AND spread_bps <= 2000)
);

CREATE INDEX idx_fx_spread_latest ON fx_spread (corridor, created_at DESC);

CREATE FUNCTION fx_forbid_change() RETURNS trigger AS
$$
BEGIN
    RAISE EXCEPTION 'exchange-rate history is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME USING ERRCODE = '23000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_fx_rate_append_only
    BEFORE UPDATE OR DELETE
    ON fx_rate
    FOR EACH ROW
EXECUTE FUNCTION fx_forbid_change();

CREATE TRIGGER trg_fx_spread_append_only
    BEFORE UPDATE OR DELETE
    ON fx_spread
    FOR EACH ROW
EXECUTE FUNCTION fx_forbid_change();
