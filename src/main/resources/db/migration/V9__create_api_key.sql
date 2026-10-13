-- API keys for the callers of PayBridge (Step 13).
-- Only a SHA-256 HASH of each key is stored, never the key itself, so a stolen copy of this table cannot be used to call the API.
CREATE TABLE api_key
(
    id         UUID                     NOT NULL,
    name       VARCHAR(100)             NOT NULL,
    key_prefix VARCHAR(12)              NOT NULL,
    key_hash   VARCHAR(64)              NOT NULL,
    role       VARCHAR(20)              NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_api_key PRIMARY KEY (id),
    CONSTRAINT uq_api_key_hash UNIQUE (key_hash),
    CONSTRAINT ck_api_key_role CHECK (role IN ('CLIENT', 'ADMIN'))
);
