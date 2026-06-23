CREATE TABLE validations (
    id             UUID         PRIMARY KEY,
    declaration_id UUID         NOT NULL,
    event_id       VARCHAR(255) NOT NULL,
    outcome        VARCHAR(32)  NOT NULL,
    failure_reason TEXT,
    rules_applied  JSONB        NOT NULL,
    validated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_validations_event_id UNIQUE (event_id)
);
