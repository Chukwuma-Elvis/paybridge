CREATE TABLE customer
(
    id         UUID                     NOT NULL,
    first_name VARCHAR(100)             NOT NULL,
    last_name  VARCHAR(100)             NOT NULL,
    email      VARCHAR(255)             NOT NULL,
    phone      VARCHAR(20)              NOT NULL,
    kyc_tier   VARCHAR(20)              NOT NULL,
    status     VARCHAR(20)              NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_customer PRIMARY KEY (id),
    CONSTRAINT uq_customer_email UNIQUE (email),
    CONSTRAINT ck_customer_kyc_tier CHECK (kyc_tier IN ('UNVERIFIED', 'BASIC', 'FULL')),
    CONSTRAINT ck_customer_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);