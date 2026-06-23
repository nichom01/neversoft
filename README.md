# Neversoft — Event-Driven Microservices PoC

A proof-of-concept demonstrating event-driven microservices using **Quarkus**, **Apache Kafka**, and **Debezium Change Data Capture**. Four services communicate asynchronously via Kafka topics with the Outbox pattern guaranteeing transactional integrity. Consumer routing is controlled by a single [`consumer-map.yml`](consumer-map.yml) at the repository root with hot-reload support in dev and staging environments.

---

## Architecture Overview

```
                  POST /declarations
                         │
                         ▼
              ┌─────────────────────┐
              │   svc-declare       │  :8080
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
│   svc-validate      │   │     svc-audit        │
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
│  svc-risk  │  │      svc-audit      │
│  :8082     │  │    Observer / log   │
└─────┬──────┘  └─────────────────────┘
      │ Debezium CDC
      ▼
┌──────────────┐
│ Kafka: risk. │
│    assessed  │
└──────┬───────┘
       │
       ▼
┌─────────────────────┐
│      svc-audit      │
│    Observer / log   │
└─────────────────────┘
```

### Key Patterns

| Pattern | Implementation |
|---|---|
| **Outbox** | Business data and event written atomically; Debezium publishes from WAL — see [ADR-002](docs/ADR/adr-002-debezium-outbox-pattern.md) |
| **At-least-once delivery** | All consumers deduplicate by `eventId` (DB unique constraint) |
| **Consumer map** | Single `consumer-map.yml` controls which channels are active; hot-reloads in dev/staging — see [ADR-003](docs/ADR/adr-003-consumer-map.md) |
| **Database-per-service** | Four isolated PostgreSQL instances |
| **Topic ordering** | Partition key is `aggregateId` — order preserved per declaration |

---

## Services

| Service | Port | Role | Mode |
|---|---|---|---|
| `svc-declare` | 8080 | REST entry point, writes declarations | Native |
| `svc-validate` | 8081 | Applies Drools business rules | JVM* |
| `svc-risk` | 8082 | Risk scoring (stub: always LOW) | Native |
| `svc-audit` | 8084 | Passive observer, logs all events | Native |

*`svc-validate` runs in JVM mode because Drools DRL compilation is incompatible with GraalVM native.

---

## Consumer Map

All Kafka topic-to-consumer bindings are declared in [`consumer-map.yml`](consumer-map.yml) at the repository root. This is the single authoritative record of which service channels are active.

```yaml
events:
  declarations.created:
    consumers:
      - service: svc-validate
        channel: declarations-created
        enabled: true
      - service: svc-audit
        channel: audit-declarations
        enabled: true
  # ... validations.completed, risk.assessed
```

Each `@Incoming` handler in `svc-validate`, `svc-risk`, and `svc-audit` checks `ConsumerMapRegistry.isEnabled(channelName)` before processing a message. Setting `enabled: false` for an entry causes that consumer to silently discard messages without a service restart (in dev/staging — see hot-reload below).

### Hot-Reload

The `ConsumerMapWatcher` bean polls the file every 30 seconds (configurable) and atomically swaps the in-memory snapshot when a valid change is detected. Hot-reload is enabled only in the profiles listed under `hot-reload.enabled-environments` in the YAML — by default `local`, `dev`, and `staging`. In `prod` the watcher never starts; the startup snapshot is permanent.

The shared library implementing this is [`lib-consumer-map`](lib-consumer-map/) — built first and installed to the local `.m2` cache before any consumer service.

```bash
# Build and install the shared library
cd lib-consumer-map
mvn install
```

See [specs/002-event-consumer-map/](specs/002-event-consumer-map/) for the full specification, design decisions, data model, and YAML schema contract.

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
| Consumer routing | `lib-consumer-map` shared library + `consumer-map.yml` |
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
# 1. Build the shared consumer-map library
cd lib-consumer-map && mvn install && cd ..

# 2. Build all service images
cd svc-declare   && mvn clean package -DskipTests && cd ..
cd svc-validate  && mvn clean package -DskipTests && cd ..
cd svc-risk      && mvn clean package -DskipTests && cd ..
cd svc-audit     && mvn clean package -DskipTests && cd ..

# 3. Start infrastructure and services
cd infra
docker compose up --wait -d

# 4. Check health
curl http://localhost:8080/q/health/ready   # svc-declare
curl http://localhost:8081/q/health/ready   # svc-validate
curl http://localhost:8082/q/health/ready   # svc-risk
curl http://localhost:8084/q/health/ready   # svc-audit
```

The Docker Compose stack bind-mounts `consumer-map.yml` into each consumer service container at `/config/consumer-map.yml`.

> The Debezium connectors must be registered after the stack is running. See [Debezium Setup](#debezium-setup) below.

### Submit a Declaration

Three customer IDs are pre-seeded in `svc-validate` and will pass validation:

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

Each service runs independently in Quarkus dev mode. There is no top-level Maven aggregator, so `cd` into the service directory first. Build and install `lib-consumer-map` once before running any consumer service.

```bash
# One-time: install the shared library
cd lib-consumer-map && mvn install && cd ..

# Run a consumer service (consumer-map.yml is auto-resolved from ../consumer-map.yml)
cd svc-validate
mvn quarkus:dev
```

In dev mode Quarkus DevServices automatically starts a PostgreSQL container. Kafka channels switch to in-memory connectors — no broker needed. The `ConsumerMapWatcher` starts and polls `../consumer-map.yml` every 30 seconds; edit the file while the service is running to hot-reload consumer routing without a restart.

> To override the file path: `mvn quarkus:dev -Dconsumer-map.file=/path/to/consumer-map.yml`

> To run all four services simultaneously for manual end-to-end testing locally, use the Docker Compose stack instead (see [Running the Full Stack](#running-the-full-stack)).

---

### Running Tests

There is no root Maven aggregator. Run tests from the relevant module directory.

> **Java version**: The system JVM may be newer than Quarkus 3.15.3's Byte Buddy supports (Java 23 max). If tests fail with `Java X is not supported by the current version of Byte Buddy`, prefix all `mvn test` commands with:
> ```bash
> JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.8/libexec/openjdk.jdk/Contents/Home mvn test
> ```

#### Consumer Map Library

```bash
cd lib-consumer-map
mvn test
```

Runs 25 unit tests covering: YAML loading, all validation rules, `isEnabled()` logic, snapshot replacement, watcher profile gating, and file-change detection.

#### Unit Tests (per service)

Pure logic tests with no external dependencies. Fast, no containers required.

```bash
cd svc-declare   && mvn test -Dtest="**/unit/**"
cd svc-validate  && mvn test -Dtest="**/unit/**"
cd svc-risk      && mvn test -Dtest="**/unit/**"
cd svc-audit     && mvn test -Dtest="**/unit/**"
```

| Service | Unit tests |
|---|---|
| `svc-declare` | `OutboxPayloadTest` |
| `svc-validate` | `CustomerValidationRuleTest`, `ValidationPayloadTest` |
| `svc-risk` | `StubRiskScorerTest`, `RiskPayloadTest` |
| `svc-audit` | `AuditDeduplicationTest` |

#### Component Tests (per service)

Per-service acceptance tests. Testcontainers provisions a real PostgreSQL instance; Kafka channels use in-memory connectors. Docker must be running.

```bash
cd svc-declare   && mvn test -Dtest="**/component/**"
cd svc-validate  && mvn test -Dtest="**/component/**"
cd svc-risk      && mvn test -Dtest="**/component/**"
cd svc-audit     && mvn test -Dtest="**/component/**"
```

| Service | Component tests |
|---|---|
| `svc-declare` | `DeclarationResourceTest` |
| `svc-validate` | `ValidationServiceTest` |
| `svc-risk` | `RiskServiceTest` |
| `svc-audit` | `AuditServiceTest` |

#### Integration Tests

End-to-end tests covering the full event flow and consumer map environment gating. Require the complete Docker Compose stack and Debezium connectors to be running.

```bash
# 1. Start the full stack (if not already running)
cd infra
docker compose up --wait -d

# 2. Register Debezium connectors (if not already registered)
curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d @debezium/declare-connector.json
curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d @debezium/validate-connector.json
curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d @debezium/risk-connector.json

# 3. Run the integration test suite
cd ../integration-tests
mvn verify -Pit
```

| Test class | What it covers |
|---|---|
| `EndToEndIT` | Happy path, validation failure, idempotency — full event flow across all four services |
| `ConsumerMapEnvGatingIT` | Verifies `ConsumerMapWatcher` is disabled in `prod` profile and produces no reload logs after YAML changes |

---

### Building

Build `lib-consumer-map` first, then each service from its own directory.

```bash
# Shared library (required before building any consumer service)
cd lib-consumer-map && mvn install

# JVM jar
cd svc-declare && mvn clean package -DskipTests

# Native image (declare, risk, audit)
cd svc-declare && mvn package -Pnative -DskipTests

# JVM image only — svc-validate cannot build native (Drools incompatibility)
cd svc-validate && mvn clean package -DskipTests
```

Docker images use a two-stage build: GraalVM Mandrel compiles the native binary in stage one; stage two copies it into a minimal `quarkus-micro-image:2.0` runtime. `svc-validate` uses an OpenJDK 21 JRE image (`Dockerfile.jvm`) instead.

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
| `svc-declare` | — | 8080 |
| `svc-validate` | — | 8081 |
| `svc-risk` | — | 8082 |
| `svc-audit` | — | 8084 |

---

## Database Schema

Each service owns an isolated database, managed by Flyway migrations.

**svc-declare** — `postgres-declare:5432/declare`
- `declarations` — incoming declarations with idempotency key
- `outbox` — transactional outbox for CDC

**svc-validate** — `postgres-validate:5433/validate`
- `customers` — reference table (3 UUIDs pre-seeded)
- `validations` — results with outcome (`PASSED`/`FAILED`) and rules applied
- `outbox`

**svc-risk** — `postgres-risk:5434/risk`
- `risk_assessments` — score (decimal), band (`LOW`/`MEDIUM`/`HIGH`)
- `outbox`

**svc-audit** — `postgres-audit:5435/audit`
- `audit_log` — immutable; raw JSON payload preserved exactly as received

---

## API Reference

`svc-declare` is the primary entry point. `svc-audit` also exposes a query API for inspecting recorded events.

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

### GET /audit (svc-audit :8084)

Query recorded audit log entries.

```
GET http://localhost:8084/audit
GET http://localhost:8084/audit?topic=declarations.created
GET http://localhost:8084/audit?aggregateId=<uuid>
GET http://localhost:8084/audit?topic=risk.assessed&aggregateId=<uuid>&page=0&size=20
GET http://localhost:8084/audit/<entry-uuid>
```

| Query param | Description | Default |
|---|---|---|
| `topic` | Filter by Kafka topic | — |
| `aggregateId` | Filter by aggregate ID | — |
| `page` | Page number (0-based) | `0` |
| `size` | Page size | `20` |

Results are ordered by `receivedAt` descending. `rawPayload` is returned as an embedded JSON object.

---

## Known Constraints (PoC Scope)

- **svc-validate runs JVM-only** — Drools runtime DRL compilation is incompatible with GraalVM native. Requires migration to the `quarkus-drools` extension.
- **Risk scorer is a stub** — `StubRiskScorer` always returns `score=0.0, band=LOW`. The `RiskScorer` interface is in place for a real implementation.
- **Consumer map: disabled consumers still receive messages** — Kafka offset advances for all delivered messages; `isEnabled: false` causes the handler to return early without processing. Message throughput is unaffected; only processing CPU is saved.
- **No dead-letter queue** — poison pill messages will block the consumer. DLQ handling is out of scope.
- **Single Kafka broker** — no multi-broker failover. Not suitable for production as-is.
- **No schema registry** — events are plain JSON; versioning is manual.
- **Outbox rows retained indefinitely** — no cleanup strategy implemented.
- **Debezium connectors require manual registration** — not auto-registered on stack start.

---

## Project Structure

```
neversoft/
├── consumer-map.yml          # Single source of truth for all topic-to-consumer bindings
├── lib-consumer-map/         # Shared library: YAML loader, CDI registry, polling watcher
├── svc-declare/              # REST entry point
├── svc-validate/             # Drools rules engine consumer
├── svc-risk/                 # Risk scoring consumer
├── svc-audit/                # Passive audit observer
├── integration-tests/        # End-to-end and env-gating integration tests
├── infra/                    # Docker Compose + Debezium connector configs
├── specs/
│   ├── 001-smart-ci-pipeline/   # CI pipeline feature spec and tasks
│   └── 002-event-consumer-map/  # Consumer map spec, plan, data model, schema contract, tasks
└── docs/
    ├── ADR/
    │   ├── adr-001-kafka-client.md          # SmallRye Reactive Messaging over Kafka Streams
    │   ├── adr-002-debezium-outbox-pattern.md  # Transactional Outbox + Debezium CDC
    │   └── adr-003-consumer-map.md          # Consumer map: polling watcher + shared library
    └── prd-microservices.md
```

---

## Design Decisions

| ADR | Decision |
|-----|----------|
| [ADR-001](docs/ADR/adr-001-kafka-client.md) | Use **SmallRye Reactive Messaging** over Kafka Streams. Services are simple consume/produce pipelines with no stateful aggregation — Kafka Streams is unnecessary overhead. |
| [ADR-002](docs/ADR/adr-002-debezium-outbox-pattern.md) | Use the **Transactional Outbox pattern** with Debezium CDC for atomic event publication. Domain records and outbox rows are written in a single database transaction; Debezium reads the WAL and publishes to Kafka, decoupling publication from request handling. |
| [ADR-003](docs/ADR/adr-003-consumer-map.md) | Use a **shared library** (`lib-consumer-map`) reading a single **`consumer-map.yml`** at the monorepo root. `isEnabled()` is a volatile read in each `@Incoming` handler; a polling file-watcher hot-reloads routing in dev/staging. Dynamic Kafka re-subscription at runtime is not supported by Quarkus SmallRye Reactive Messaging. |
