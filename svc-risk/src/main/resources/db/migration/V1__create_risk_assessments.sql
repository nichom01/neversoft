CREATE TABLE risk_assessments (
    id             UUID          PRIMARY KEY,
    declaration_id UUID          NOT NULL,
    validation_id  UUID          NOT NULL,
    event_id       VARCHAR(255)  NOT NULL,
    score          DECIMAL(5,2)  NOT NULL,
    band           VARCHAR(32)   NOT NULL,
    assessed_at    TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_risk_assessments_event_id UNIQUE (event_id)
);
