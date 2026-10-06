-- Source of 9-digit account serial numbers (the check digit is added by the application).
CREATE SEQUENCE account_number_seq START WITH 100000001 INCREMENT BY 1 NO CYCLE;

CREATE TABLE account
(
    id                UUID                     NOT NULL,
    account_number    VARCHAR(10),
    code              VARCHAR(40),
    account_type      VARCHAR(20)              NOT NULL,
    owner_customer_id UUID,
    currency          VARCHAR(3)               NOT NULL,
    balance_minor     BIGINT                   NOT NULL DEFAULT 0,
    status            VARCHAR(20)              NOT NULL,
    version           BIGINT                   NOT NULL DEFAULT 0,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_account PRIMARY KEY (id),
    CONSTRAINT uq_account_number UNIQUE (account_number),
    CONSTRAINT uq_account_code UNIQUE (code),
    CONSTRAINT fk_account_customer FOREIGN KEY (owner_customer_id) REFERENCES customer (id),
    CONSTRAINT ck_account_type CHECK (account_type IN ('CUSTOMER', 'SYSTEM')),
    CONSTRAINT ck_account_status CHECK (status IN ('ACTIVE', 'FROZEN')),
    CONSTRAINT ck_account_currency CHECK (currency IN ('NGN', 'USD')),
    -- last line of defence: a customer's balance can never be negative, whatever the code does
    CONSTRAINT ck_account_customer_not_negative CHECK (account_type <> 'CUSTOMER' OR balance_minor >= 0),
    -- a customer account has a number and an owner; a system account has a code and neither
    CONSTRAINT ck_account_identity CHECK (
        (account_type = 'CUSTOMER' AND account_number IS NOT NULL AND owner_customer_id IS NOT NULL AND code IS NULL)
        OR
        (account_type = 'SYSTEM' AND code IS NOT NULL AND account_number IS NULL AND owner_customer_id IS NULL)
    )
);

CREATE INDEX idx_account_owner ON account (owner_customer_id);

-- The bank's own internal accounts. They may hold a negative balance (that just means "we owe" or "we gave out").
INSERT INTO account (id, code, account_type, currency, balance_minor, status, version, created_at, updated_at)
VALUES (gen_random_uuid(), 'FUNDING_SOURCE', 'SYSTEM', 'NGN', 0, 'ACTIVE', 0, now(), now()),
       (gen_random_uuid(), 'SUSPENSE', 'SYSTEM', 'NGN', 0, 'ACTIVE', 0, now(), now()),
       (gen_random_uuid(), 'PAYSTACK_SETTLEMENT', 'SYSTEM', 'NGN', 0, 'ACTIVE', 0, now(), now()),
       (gen_random_uuid(), 'FEES', 'SYSTEM', 'NGN', 0, 'ACTIVE', 0, now(), now());
