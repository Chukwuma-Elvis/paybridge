-- How much each customer has sent today (Africa/Lagos days), used to enforce daily limits.
CREATE TABLE daily_usage
(
    id          UUID   NOT NULL,
    customer_id UUID   NOT NULL,
    usage_date  DATE   NOT NULL,
    total_minor BIGINT NOT NULL,

    CONSTRAINT pk_daily_usage PRIMARY KEY (id),
    CONSTRAINT uq_daily_usage_customer_date UNIQUE (customer_id, usage_date),
    CONSTRAINT fk_daily_usage_customer FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT ck_daily_usage_not_negative CHECK (total_minor >= 0)
);
