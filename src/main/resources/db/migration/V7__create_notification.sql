-- A record of every alert we "sent". Real SMS/email providers are plugged in later behind NotificationSender.
CREATE TABLE notification
(
    id          UUID                     NOT NULL,
    customer_id UUID                     NOT NULL,
    channel     VARCHAR(10)              NOT NULL,
    recipient   VARCHAR(255)             NOT NULL,
    event_type  VARCHAR(40)              NOT NULL,
    reference   VARCHAR(100)             NOT NULL,
    message     VARCHAR(500)             NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_notification PRIMARY KEY (id),
    CONSTRAINT fk_notification_customer FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT ck_notification_channel CHECK (channel IN ('SMS', 'EMAIL')),
    -- the same event is never announced twice to the same customer on the same channel,
    -- even if the event system delivers it again after a crash
    CONSTRAINT uq_notification_once UNIQUE (customer_id, event_type, reference, channel)
);

CREATE INDEX idx_notification_customer ON notification (customer_id, created_at);
