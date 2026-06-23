# System Architecture

```mermaid
flowchart TD
    Client(["Client"])

    subgraph svc-declare["svc-declare  :8080"]
        DecAPI["REST API"]
        DecDB[("postgres-declare\n:5432")]
        DecAPI -->|"atomic write + outbox"| DecDB
    end

    subgraph svc-validate["svc-validate  :8081"]
        ValSvc["Drools Rules Engine"]
        ValDB[("postgres-validate\n:5433")]
        ValSvc -->|"atomic write + outbox"| ValDB
    end

    subgraph svc-risk["svc-risk  :8082"]
        RiskSvc["Risk Scorer"]
        RiskDB[("postgres-risk\n:5434")]
        RiskSvc -->|"atomic write + outbox"| RiskDB
    end

    subgraph svc-audit["svc-audit  :8084"]
        AuditSvc["Event Observer"]
        AuditDB[("postgres-audit\n:5435")]
        AuditSvc -->|"write"| AuditDB
    end

    Debezium["Debezium Connect  :8083"]

    T1(["declarations.created"])
    T2(["validations.completed"])
    T3(["risk.assessed"])

    Client -->|"POST /declarations"| DecAPI
    DecDB -->|"WAL"| Debezium
    ValDB -->|"WAL"| Debezium
    RiskDB -->|"WAL"| Debezium

    Debezium -->|"outbox-connector"| T1
    Debezium -->|"outbox-connector"| T2
    Debezium -->|"outbox-connector"| T3

    T1 --> ValSvc
    T1 --> AuditSvc
    T2 --> RiskSvc
    T2 --> AuditSvc
    T3 --> AuditSvc
```

## Key

| Symbol | Meaning |
|--------|---------|
| Rectangle | Service or component |
| Cylinder | PostgreSQL database |
| Stadium | Kafka topic |
| `WAL` edge | Debezium reads PostgreSQL Write-Ahead Log via logical replication |
| `outbox-connector` edge | Debezium Outbox EventRouter SMT publishes rows to Kafka |
| `atomic write + outbox` | Business record and outbox row written in a single DB transaction |
