-- Quotes (Step 16): a locked rate that can be used exactly once.
-- Single use is enforced HERE, by the database, not only in code: a unique "consumed_by" and a conditional update mean two
-- simultaneous attempts to use one quote cannot both succeed.
CREATE TABLE fx_quote
(
    id              UUID                     NOT NULL,
    reference       VARCHAR(40)              NOT NULL,
    corridor        VARCHAR(20)              NOT NULL,
    sell_minor      BIGINT                   NOT NULL,
    fee_minor       BIGINT                   NOT NULL,
    buy_minor       BIGINT                   NOT NULL,
    mid_rate        NUMERIC(18, 6)           NOT NULL,
    customer_rate   NUMERIC(18, 6)           NOT NULL,
    spread_bps      INTEGER                  NOT NULL,
    rate_source     VARCHAR(60)              NOT NULL,
    rate_as_of      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    status          VARCHAR(12)              NOT NULL,
    consumed_by     UUID,
    consumed_at     TIMESTAMP WITH TIME ZONE,
    requested_by    VARCHAR(100)             NOT NULL,
    idempotency_key VARCHAR(100)             NOT NULL,
    request_hash    VARCHAR(64)              NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_fx_quote PRIMARY KEY (id),
    CONSTRAINT uq_fx_quote_reference UNIQUE (reference),
    CONSTRAINT uq_fx_quote_consumed_by UNIQUE (consumed_by),
    CONSTRAINT uq_fx_quote_request UNIQUE (requested_by, idempotency_key),
    CONSTRAINT ck_fx_quote_status CHECK (status IN ('OPEN', 'CONSUMED', 'CANCELLED')),
    CONSTRAINT ck_fx_quote_amounts CHECK (sell_minor > 0 AND fee_minor >= 0 AND buy_minor > 0 AND fee_minor < sell_minor),
    CONSTRAINT ck_fx_quote_consumed CHECK ((status = 'CONSUMED') = (consumed_by IS NOT NULL))
);

CREATE INDEX idx_fx_quote_expiry ON fx_quote (status, expires_at);
