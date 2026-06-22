CREATE TABLE declarations (
    id              UUID        PRIMARY KEY,
    customer_id     UUID        NOT NULL,
    payload         JSONB       NOT NULL,
    status          VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_declarations_idempotency_key UNIQUE (idempotency_key)
);
