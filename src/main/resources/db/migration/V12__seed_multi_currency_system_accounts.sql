-- Data for the multi-currency foundation (Step 14). Kept apart from the schema change in V11, as the migration rules ask.
-- Each system account holds a single currency. Accounts for individual corridor partners are created at run time, not here.
INSERT INTO account (id, code, account_type, currency, balance_minor, status, version, created_at, updated_at)
VALUES (gen_random_uuid(), 'FX_POSITION_USD', 'SYSTEM', 'USD', 0, 'ACTIVE', 0, now(), now()),
       (gen_random_uuid(), 'FX_POSITION_NGN', 'SYSTEM', 'NGN', 0, 'ACTIVE', 0, now(), now()),
       (gen_random_uuid(), 'REMITTANCE_CLEARING_USD', 'SYSTEM', 'USD', 0, 'ACTIVE', 0, now(), now()),
       (gen_random_uuid(), 'REMITTANCE_CLEARING_NGN', 'SYSTEM', 'NGN', 0, 'ACTIVE', 0, now(), now()),
       (gen_random_uuid(), 'SETTLEMENT_ADJUSTMENT_NGN', 'SYSTEM', 'NGN', 0, 'ACTIVE', 0, now(), now()),
       (gen_random_uuid(), 'SETTLEMENT_ADJUSTMENT_USD', 'SYSTEM', 'USD', 0, 'ACTIVE', 0, now(), now());
