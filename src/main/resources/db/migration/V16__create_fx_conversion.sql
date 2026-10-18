-- A conversion (Step 18): the link between a consumed quote and the TWO ledger entries that carried it out.
-- The ledger itself stays untouched; this table is what ties the USD leg and the NGN leg together.
CREATE TABLE fx_conversion
(
    id                UUID                     NOT NULL,
    quote_id          UUID                     NOT NULL,
    sell_minor        BIGINT                   NOT NULL,
    fee_minor         BIGINT                   NOT NULL,
    buy_minor         BIGINT                   NOT NULL,
    usd_source_id     UUID                     NOT NULL,
    ngn_target_id     UUID                     NOT NULL,
    usd_entry_id      UUID                     NOT NULL,
    ngn_entry_id      UUID                     NOT NULL,
    status            VARCHAR(10)              NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    reversed_at       TIMESTAMP WITH TIME ZONE,

    CONSTRAINT pk_fx_conversion PRIMARY KEY (id),
    -- one quote can be converted once: the database refuses a second conversion for it
    CONSTRAINT uq_fx_conversion_quote UNIQUE (quote_id),
    CONSTRAINT fk_fx_conversion_quote FOREIGN KEY (quote_id) REFERENCES fx_quote (id),
    CONSTRAINT fk_fx_conversion_usd_entry FOREIGN KEY (usd_entry_id) REFERENCES journal_entry (id),
    CONSTRAINT fk_fx_conversion_ngn_entry FOREIGN KEY (ngn_entry_id) REFERENCES journal_entry (id),
    CONSTRAINT ck_fx_conversion_status CHECK (status IN ('POSTED', 'REVERSED')),
    CONSTRAINT ck_fx_conversion_reversed CHECK ((status = 'REVERSED') = (reversed_at IS NOT NULL))
);

CREATE INDEX idx_fx_conversion_usd_entry ON fx_conversion (usd_entry_id);
CREATE INDEX idx_fx_conversion_ngn_entry ON fx_conversion (ngn_entry_id);
