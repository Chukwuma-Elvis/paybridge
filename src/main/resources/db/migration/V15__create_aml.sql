-- Compliance extension (Step 17): screening cases, USD limits, velocity events.

-- A case is the permanent record of a screening result that was not CLEAR. It is never deleted, and once decided it never changes.
CREATE TABLE screening_case
(
    id              UUID                     NOT NULL,
    context_ref     VARCHAR(100)             NOT NULL,
    party_role      VARCHAR(20)              NOT NULL,
    party_name      VARCHAR(200)             NOT NULL,
    party_country   VARCHAR(2),
    party_birth     DATE,
    outcome         VARCHAR(10)              NOT NULL,
    match_details   TEXT                     NOT NULL,
    status          VARCHAR(10)              NOT NULL,
    decision_reason VARCHAR(500),
    decided_by      VARCHAR(100),
    decided_at      TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_screening_case PRIMARY KEY (id),
    CONSTRAINT uq_screening_case_context UNIQUE (context_ref, party_role),
    CONSTRAINT ck_screening_case_outcome CHECK (outcome IN ('REVIEW', 'BLOCK')),
    CONSTRAINT ck_screening_case_status CHECK (status IN ('OPEN', 'APPROVED', 'REJECTED')),
    -- a decision always carries who, when and a written reason
    CONSTRAINT ck_screening_case_decided CHECK (
        (status = 'OPEN' AND decided_by IS NULL AND decided_at IS NULL AND decision_reason IS NULL)
        OR
        (status <> 'OPEN' AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND length(trim(decision_reason)) > 0)
    ),
    -- a BLOCK can only be rejected here: approving one is an escalation that happens outside the system
    CONSTRAINT ck_screening_case_block CHECK (NOT (outcome = 'BLOCK' AND status = 'APPROVED'))
);

CREATE INDEX idx_screening_case_status ON screening_case (status, created_at);

CREATE FUNCTION screening_case_guard() RETURNS trigger AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'screening cases are never deleted' USING ERRCODE = '23000';
    END IF;
    IF OLD.status <> 'OPEN' THEN
        RAISE EXCEPTION 'a decided screening case never changes' USING ERRCODE = '23000';
    END IF;
    IF NEW.id <> OLD.id OR NEW.context_ref <> OLD.context_ref OR NEW.party_role <> OLD.party_role
        OR NEW.party_name <> OLD.party_name OR NEW.outcome <> OLD.outcome OR NEW.match_details <> OLD.match_details
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'only the decision of a screening case may be filled in' USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_screening_case_guard
    BEFORE UPDATE OR DELETE
    ON screening_case
    FOR EACH ROW
EXECUTE FUNCTION screening_case_guard();

-- How much each sender / recipient has moved, per day ('D') and per month ('M'), in US cents. Reserved with one statement.
CREATE TABLE usd_usage
(
    id           UUID         NOT NULL,
    scope        VARCHAR(10)  NOT NULL,
    subject_key  VARCHAR(120) NOT NULL,
    period_kind  CHAR(1)      NOT NULL,
    period_start DATE         NOT NULL,
    total_minor  BIGINT       NOT NULL,

    CONSTRAINT pk_usd_usage PRIMARY KEY (id),
    CONSTRAINT uq_usd_usage UNIQUE (scope, subject_key, period_kind, period_start),
    CONSTRAINT ck_usd_usage_kind CHECK (period_kind IN ('D', 'M')),
    CONSTRAINT ck_usd_usage_not_negative CHECK (total_minor >= 0)
);

-- One row per attempt, to spot bursts and many-just-under-the-threshold patterns. event_ref makes recording idempotent.
CREATE TABLE velocity_event
(
    id           UUID                     NOT NULL,
    subject_key  VARCHAR(120)             NOT NULL,
    event_ref    VARCHAR(100)             NOT NULL,
    amount_minor BIGINT                   NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_velocity_event PRIMARY KEY (id),
    CONSTRAINT uq_velocity_event UNIQUE (subject_key, event_ref)
);

CREATE INDEX idx_velocity_event_subject ON velocity_event (subject_key, created_at);
