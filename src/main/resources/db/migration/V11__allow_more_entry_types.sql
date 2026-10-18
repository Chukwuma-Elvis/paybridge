-- Schema change for the multi-currency foundation (Step 14): the ledger accepts the new kinds of entry.
-- Only the allowed list grows; no existing row or entry type changes meaning.
ALTER TABLE journal_entry DROP CONSTRAINT ck_journal_entry_type;

ALTER TABLE journal_entry ADD CONSTRAINT ck_journal_entry_type CHECK (entry_type IN
    ('FUNDING', 'TRANSFER', 'EXTERNAL_HOLD', 'EXTERNAL_SETTLEMENT', 'REVERSAL',
     'REMITTANCE_FUNDING', 'FX_CONVERSION_USD', 'FX_CONVERSION_NGN', 'REMITTANCE_PAYOUT',
     'SETTLEMENT_PAYMENT', 'SETTLEMENT_ADJUSTMENT'));
