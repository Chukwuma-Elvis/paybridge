-- A journal entry is one business event (a transfer, a funding, a reversal) ...
CREATE TABLE journal_entry
(
    id                UUID                     NOT NULL,
    entry_type        VARCHAR(40)              NOT NULL,
    reference         VARCHAR(100)             NOT NULL,
    description       VARCHAR(255),
    reverses_entry_id UUID,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_journal_entry PRIMARY KEY (id),
    -- the same business event can only be posted once (this is what makes retries safe)
    CONSTRAINT uq_journal_entry_type_reference UNIQUE (entry_type, reference),
    CONSTRAINT fk_journal_entry_reverses FOREIGN KEY (reverses_entry_id) REFERENCES journal_entry (id),
    CONSTRAINT ck_journal_entry_type CHECK (entry_type IN
        ('FUNDING', 'TRANSFER', 'EXTERNAL_HOLD', 'EXTERNAL_SETTLEMENT', 'REVERSAL'))
);

-- ... and its lines are the individual debits (negative) and credits (positive). They must add up to zero.
CREATE TABLE ledger_line
(
    id           UUID                     NOT NULL,
    entry_id     UUID                     NOT NULL,
    account_id   UUID                     NOT NULL,
    amount_minor BIGINT                   NOT NULL,
    currency     VARCHAR(3)               NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_ledger_line PRIMARY KEY (id),
    CONSTRAINT fk_ledger_line_entry FOREIGN KEY (entry_id) REFERENCES journal_entry (id),
    CONSTRAINT fk_ledger_line_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT ck_ledger_line_not_zero CHECK (amount_minor <> 0)
);

CREATE INDEX idx_ledger_line_account ON ledger_line (account_id, created_at);
CREATE INDEX idx_ledger_line_entry ON ledger_line (entry_id);

-- Safety net 1: at commit time every journal entry must balance, whatever the application did.
CREATE FUNCTION ledger_check_entry_balanced() RETURNS trigger AS
$$
BEGIN
    IF (SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_line WHERE entry_id = NEW.entry_id) <> 0 THEN
        RAISE EXCEPTION 'journal entry % does not balance', NEW.entry_id USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_line_balanced
    AFTER INSERT
    ON ledger_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION ledger_check_entry_balanced();

-- Safety net 2: the ledger is a permanent record. History is never edited or deleted;
-- mistakes are fixed by posting a reversing entry.
CREATE FUNCTION ledger_forbid_change() RETURNS trigger AS
$$
BEGIN
    RAISE EXCEPTION 'the ledger is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME USING ERRCODE = '23000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_line_append_only
    BEFORE UPDATE OR DELETE
    ON ledger_line
    FOR EACH ROW
EXECUTE FUNCTION ledger_forbid_change();

CREATE TRIGGER trg_journal_entry_append_only
    BEFORE UPDATE OR DELETE
    ON journal_entry
    FOR EACH ROW
EXECUTE FUNCTION ledger_forbid_change();
