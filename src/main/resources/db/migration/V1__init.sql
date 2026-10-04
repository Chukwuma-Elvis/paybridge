CREATE TABLE event_publication
(
    id                     UUID                     NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    listener_id            TEXT                     NOT NULL,
    serialized_event       TEXT                     NOT NULL,
    event_type             TEXT                     NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    completion_attempts    INTEGER                  NOT NULL DEFAULT 0,
    status                 VARCHAR(32),
    PRIMARY KEY (id)
);

CREATE INDEX event_publication_by_completion_date_idx
    ON event_publication (completion_date);

CREATE INDEX event_publication_serialized_event_hash_idx
    ON event_publication USING hash (serialized_event);