CREATE TABLE audit_log (
    id           UUID         PRIMARY KEY,
    event_id     VARCHAR(255) NOT NULL,
    topic        VARCHAR(255) NOT NULL,
    event_type   VARCHAR(255) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    raw_payload  JSONB        NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_audit_log_event_id UNIQUE (event_id)
);
