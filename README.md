# Neversoft — Event-Driven Microservices PoC

A proof-of-concept demonstrating event-driven microservices using **Quarkus**, **Apache Kafka**, and **Debezium Change Data Capture**. Four services communicate asynchronously via Kafka topics with the Outbox pattern guaranteeing transactional integrity.

---

## Architecture Overview

```
                  POST /declarations
                         │
                         ▼
              ┌─────────────────────┐
              │   declare-svc       │  :8080
              │   REST → Outbox     │
              └──────────┬──────────┘
                         │ Debezium CDC
                         ▼
              ┌──────────────────────────┐
              │  Kafka: declarations.    │
              │         created          │
              └────────┬─────────────────┘
                       │
           ┌───────────┴───────────┐
           │                       │
           ▼                       ▼
┌─────────────────────┐   ┌─────────────────────┐
│   validate-svc      │   │     audit-svc        │
│   Drools rules      │   │   Observer / log     │
│   :8081             │   │   :8084              │
└──────────┬──────────┘   └─────────────────────┘
           │ Debezium CDC
           ▼
┌──────────────────────────┐
│  Kafka: validations.     │
│         completed        │
└────────┬─────────────────┘
         │
  ┌──────┴──────────┐
  │                 │
  ▼                 ▼
┌────────────┐  ┌─────────────────────┐
│  risk-svc  │  │      audit-svc      │
│  :8082     │  │    Observer / log   │
└─────┬──────┘  └─────────────────────┘
      │ Debezium CDC
      ▼
┌──────────────────┐
│ Kafka: risk.     │
│        assessed  │
└────────┬─────────┘
         │
         ▼
┌─────────────────────┐
│      audit-svc      │
│    Observer / log   │
└─────────────────────┘
```

### Key Patterns

| Pattern | Implementation |
|---|---|
| **Outbox** | Business data and event written atomically; Debezium publishes from WAL |
| **At-least-once delivery** | All consumers deduplicate by `eventId` (DB unique constraint) |
| **Database-per-service** | Four isolated PostgreSQL instances |
| **Topic ordering** | Partition key is `aggregateId` — order preserved per declaration |

---

## Services

| Service | Port | Role | Mode |
|---|---|---|---|
| `declare-svc` | 8080 | REST entry point, writes declarations | Native |
| `validate-svc` | 8081 | Applies Drools business rules | JVM* |
| `risk-svc` | 8082 | Risk scoring (stub: always LOW) | Native |
| `audit-svc` | 8084 | Passive observer, logs all events | Native |

*`validate-svc` runs in JVM mode because Drools DRL compilation is incompatible with GraalVM native. Migration to `quarkus-drools` is required for native support.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Quarkus 3.15.3 |
| Build | Maven |
| REST | RESTEasy Reactive + Jackson |
| Rules engine | Drools 9.44.0.Final (DRL) |
| Messaging | Apache Kafka + SmallRye Reactive Messaging |
| CDC | Debezium 2.6 (PostgreSQL connector, Outbox EventRouter SMT) |
| Database | PostgreSQL 16 + Hibernate ORM Panache + Flyway |
| Containers | Docker + GraalVM Mandrel (multi-stage builds) |
| Testing | JUnit 5 + Testcontainers + REST Assured + Awaitility |
| Observability | Quarkus SmallRye Health + JSON structured logging |

---

## Prerequisites

- **Docker** (with Compose v2)
- **Java 21** and **Maven 3.9+** (for local development or building images)
- **GraalVM / Mandrel** (only if building native images locally)

---

## Running the Full Stack

```bash
# 1. Build all service images
mvn clean package -DskipTests

# 2. Start infrastructure and services
cd infra
docker compose up --wait -d

# 3. Check health
curl http://localhost:8080/q/health/ready   # declare-svc
curl http://localhost:8081/q/health/ready   # validate-svc
curl http://localhost:8082/q/health/ready   # risk-svc
curl http://localhost:8084/q/health/ready   # audit-svc
```

> The Debezium connectors must be registered after the stack is running. See [Debezium Setup](#debezium-setup) below.

### Submit a Declaration

Three customer IDs are pre-seeded in `validate-svc` and will pass validation:

```bash
# Happy path — known customer, passes validation
curl -X POST http://localhost:8080/declarations \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "550e8400-e29b-41d4-a716-446655440001",
    "idempotencyKey": "my-unique-key-001",
    "payload": {}
  }'
```

```bash
# Validation failure — unknown customer
curl -X POST http://localhost:8080/declarations \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "00000000-0000-0000-0000-000000000000",
    "idempotencyKey": "my-unique-key-002",
    "payload": {}
  }'
```

```bash
# Idempotent retry — same idempotencyKey returns 200 instead of 201
curl -X POST http://localhost:8080/declarations \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "550e8400-e29b-41d4-a716-446655440001",
    "idempotencyKey": "my-unique-key-001",
    "payload": {}
  }'
```

### Inspect Kafka Topics

```bash
# List topics
docker exec kafka kafka-topics --bootstrap-server localhost:29092 --list

# Tail a topic
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:29092 \
  --topic declarations.created \
  --from-beginning
```

---

## Debezium Setup

After the stack is running, register the three CDC connectors:

```bash
# Declare connector
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @infra/debezium/declare-connector.json

# Validate connector
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @infra/debezium/validate-connector.json

# Risk connector
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @infra/debezium/risk-connector.json

# Check connector status
curl http://localhost:8083/connectors?expand=status | jq .
```

Each connector monitors its service's `outbox` table via PostgreSQL WAL (`wal_level=logical`) and routes events to Kafka using the Outbox EventRouter SMT.

---

## Developer Guide

### Starting a Service Locally

Each service runs independently in Quarkus dev mode. There is no top-level Maven aggregator, so `cd` into the service directory first.

```bash
cd declare-svc    # or validate-svc / risk-svc / audit-svc
mvn quarkus:dev
```

In dev mode Quarkus DevServices automatically starts a PostgreSQL container for the service's database. Kafka channels switch to in-memory connectors — no broker needed. Live reload is enabled; changes to source files are picked up without restarting.

> To run all four services simultaneously for manual end-to-end testing locally, use the Docker Compose stack instead (see [Running the Full Stack](#running-the-full-stack)).

---

### Running Tests

There is no root Maven aggregator. Each service has its own test suite; run them from the service directory.

#### Unit Tests

Pure logic tests with no external dependencies. Fast, no containers required.

```bash
# Run unit tests for a single service
cd declare-svc
mvn test -pl . -Dtest="**/unit/**"

# Or for any service
cd validate-svc && mvn test -Dtest="**/unit/**"
cd risk-svc     && mvn test -Dtest="**/unit/**"
cd audit-svc    && mvn test -Dtest="**/unit/**"
```

| Service | Unit tests |
|---|---|
| `declare-svc` | `OutboxPayloadTest` |
| `validate-svc` | `CustomerValidationRuleTest`, `ValidationPayloadTest` |
| `risk-svc` | `StubRiskScorerTest`, `RiskPayloadTest` |
| `audit-svc` | `AuditDeduplicationTest` |

#### Component Tests

Per-service acceptance tests. Testcontainers provisions a real PostgreSQL instance; Kafka channels use in-memory connectors. Docker must be running.

```bash
cd declare-svc
mvn test -Dtest="**/component/**"

cd validate-svc && mvn test -Dtest="**/component/**"
cd risk-svc     && mvn test -Dtest="**/component/**"
cd audit-svc    && mvn test -Dtest="**/component/**"
```

| Service | Component tests |
|---|---|
| `declare-svc` | `DeclarationResourceTest` |
| `validate-svc` | `ValidationServiceTest` |
| `risk-svc` | `RiskServiceTest` |
| `audit-svc` | `AuditServiceTest` |

To run unit and component tests together for a service:

```bash
cd declare-svc
mvn test
```

#### Integration Tests

End-to-end tests covering the full event flow through all four services and Debezium. These require the complete Docker Compose stack to be running and the Debezium connectors to be registered.

```bash
# 1. Start the full stack (if not already running)
cd infra
docker compose up --wait -d

# 2. Register Debezium connectors (if not already registered)
curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d @debezium/declare-connector.json
curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d @debezium/validate-connector.json
curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d @debezium/risk-connector.json

# 3. Run the integration test suite
cd ../it-tests
mvn verify -Pit
```

Three scenarios are covered by `EndToEndIT`:

1. **Happy path** — known customer, declaration flows through validate → risk → audit
2. **Validation failure** — unknown customer, risk step is skipped, audit records the failure
3. **Idempotency** — duplicate `idempotencyKey` is rejected without duplicate processing downstream

Tests poll with up to 10 seconds of tolerance (Awaitility) to account for async propagation through Kafka and Debezium.

---

### Building

There is no top-level aggregator build. Build each service from its own directory.

```bash
# JVM jar (all services)
cd declare-svc && mvn clean package -DskipTests

# Native image (declare, risk, audit)
cd declare-svc && mvn package -Pnative -DskipTests

# JVM image only — validate-svc cannot build native (Drools incompatibility)
cd validate-svc && mvn clean package -DskipTests
```

Docker images use a two-stage build: GraalVM Mandrel compiles the native binary in stage one; stage two copies it into a minimal `quarkus-micro-image:2.0` runtime targeting < 100 MB. `validate-svc` uses an OpenJDK 21 JRE image (`Dockerfile.jvm`) instead.

---

## Infrastructure

```
infra/
├── docker-compose.yml
└── debezium/
    ├── declare-connector.json
    ├── validate-connector.json
    └── risk-connector.json
```

| Container | Image | Port |
|---|---|---|
| `zookeeper` | confluentinc/cp-zookeeper:7.6.1 | 2181 |
| `kafka` | confluentinc/cp-kafka:7.6.1 | 9092, 29092 |
| `kafka-setup` | confluentinc/cp-kafka:7.6.1 | — (init) |
| `postgres-declare` | postgres:16 | 5432 |
| `postgres-validate` | postgres:16 | 5433 |
| `postgres-risk` | postgres:16 | 5434 |
| `postgres-audit` | postgres:16 | 5435 |
| `debezium-connect` | debezium/connect:2.6 | 8083 |
| `declare-svc` | — | 8080 |
| `validate-svc` | — | 8081 |
| `risk-svc` | — | 8082 |
| `audit-svc` | — | 8084 |

---

## Database Schema

Each service owns an isolated database, managed by Flyway migrations.

**declare-svc** — `postgres-declare:5432/declare`
- `declarations` — incoming declarations with idempotency key
- `outbox` — transactional outbox for CDC

**validate-svc** — `postgres-validate:5433/validate`
- `customers` — reference table (3 UUIDs pre-seeded)
- `validations` — results with outcome (`PASSED`/`FAILED`) and rules applied
- `outbox`

**risk-svc** — `postgres-risk:5434/risk`
- `risk_assessments` — score (decimal), band (`LOW`/`MEDIUM`/`HIGH`)
- `outbox`

**audit-svc** — `postgres-audit:5435/audit`
- `audit_log` — immutable; raw JSON payload preserved exactly as received

---

## API Reference

`declare-svc` is the only service with a public HTTP API. All others are event-driven.

### POST /declarations

```
POST http://localhost:8080/declarations
Content-Type: application/json

{
  "customerId":     "UUID",
  "idempotencyKey": "string",
  "payload":        {}
}
```

| Response | Meaning |
|---|---|
| `201 Created` | Declaration accepted, body contains declaration UUID |
| `200 OK` | Duplicate `idempotencyKey` — returns existing UUID |
| `400 Bad Request` | Validation error on request body |

Health endpoints are available on all services at `/q/health` and `/q/health/ready`.

---

## Known Constraints (PoC Scope)

- **validate-svc runs JVM-only** — Drools runtime DRL compilation is incompatible with GraalVM native. Requires migration to the `quarkus-drools` extension.
- **Risk scorer is a stub** — `StubRiskScorer` always returns `score=0.0, band=LOW`. The `RiskScorer` interface is in place for a real implementation.
- **No dead-letter queue** — poison pill messages will block the consumer. DLQ handling is out of scope.
- **Single Kafka broker** — no multi-broker failover. Not suitable for production as-is.
- **No schema registry** — events are plain JSON; versioning is manual.
- **No audit query API** — `audit-svc` has no REST endpoint; data is accessible via direct DB connection only.
- **Outbox rows retained indefinitely** — no cleanup strategy implemented.
- **Debezium connectors require manual registration** — not auto-registered on stack start.

---

## Project Structure

```
neversoft/
├── declare-svc/          # REST entry point
├── validate-svc/         # Drools rules engine consumer
├── risk-svc/             # Risk scoring consumer
├── audit-svc/            # Passive audit observer
├── it-tests/             # End-to-end integration tests
├── infra/                # Docker Compose + Debezium configs
└── docs/
    ├── prd-microservices.md   # Product requirements
    ├── plan.md                # Implementation roadmap
    └── adr-001-kafka-client.md  # ADR: SmallRye vs Kafka Streams
```

---

## Design Decisions

See [`docs/adr-001-kafka-client.md`](docs/adr-001-kafka-client.md) for the decision to use **SmallRye Reactive Messaging** over Kafka Streams. Services are simple consume/produce pipelines with no stateful aggregation, making Kafka Streams unnecessary overhead.
