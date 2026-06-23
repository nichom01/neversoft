# Implementation Plan
## Event-Driven Microservices Platform (PoC)

**Based on:** `prd-microservices.md` v0.3  
**Date:** 2026-06-22

---

## Repository Structure

A single mono-repo houses all four services, shared infrastructure config, and tests.

```
neversoft/
├── docs/
│   └── plan.md
├── infra/
│   ├── docker-compose.yml
│   └── debezium/
│       ├── connector-declare.json
│       ├── connector-validate.json
│       └── connector-risk.json
├── svc-declare/
├── svc-validate/
├── svc-risk/
├── svc-audit/
└── prd-microservices.md
```

Each service is a standalone Quarkus Maven project with its own `pom.xml`, `Dockerfile`, and `src/` tree.

---

## Phase 1 — Infrastructure Skeleton

**Goal:** A working Docker Compose stack before any application code is written.

### Tasks

1. **`infra/docker-compose.yml`** — define all infrastructure containers:
   - `zookeeper` (Confluent)
   - `kafka` (single-broker, depends on zookeeper)
   - `postgres-declare`, `postgres-validate`, `postgres-risk`, `postgres-audit` — each with `wal_level = logical` set via `command` override
   - `debezium-connect` (Debezium image, depends on kafka + all postgres instances)

2. **Kafka topics** — create the three topics at startup via a `kafka-setup` init container:
   - `declarations.created` (1 partition, 7-day retention)
   - `validations.completed` (1 partition, 7-day retention)
   - `risk.assessed` (1 partition, 7-day retention)

3. **Smoke test** — verify the stack starts cleanly and Debezium Connect REST API is reachable before moving to Phase 2.

### Acceptance criteria
- `docker compose up` reaches healthy state with no restart loops
- Debezium Connect REST endpoint responds at `http://localhost:8083/connectors`

---

## Phase 2 — ADR: Kafka Client Library

**Goal:** Resolve Open Question #1 before writing any consumer/producer code.

### Decision required
Choose between:
- **SmallRye Reactive Messaging** (`quarkus-smallrye-reactive-messaging-kafka`) — reactive, declarative `@Incoming`/`@Outgoing` annotations, native image friendly
- **Quarkus Kafka Streams** — more appropriate for stateful stream processing; heavier for simple consume/produce patterns

**Recommendation:** SmallRye Reactive Messaging. The PoC services are simple event consumers and producers with no stateful aggregation — Kafka Streams is over-engineered here. SmallRye also has first-class Quarkus native image support.

Capture the decision in `docs/adr-001-kafka-client.md` before proceeding to Phase 3.

---

## Phase 3 — Declare Service

**Goal:** Entry point service. Accepts a REST POST, persists a declaration, writes an outbox record atomically.

### Tasks

1. **Scaffold** — `mvn io.quarkus:quarkus-maven-plugin:create` with extensions:
   - `quarkus-resteasy-reactive-jackson`
   - `quarkus-hibernate-orm-panache`
   - `quarkus-jdbc-postgresql`
   - `quarkus-flyway` (schema migrations)

2. **Domain model**
   - `Declaration` entity (`id`, `customerId`, `payload`, `status`, `createdAt`)
   - `OutboxEntry` entity (standard outbox schema)

3. **Database migrations** (`src/main/resources/db/migration/`)
   - `V1__create_declarations.sql`
   - `V2__create_outbox.sql`

4. **REST endpoint** — `POST /declarations`
   - Validate request (idempotency key in header or body)
   - Write `Declaration` + `OutboxEntry` in a single `@Transactional` method
   - Return `201 Created` with declaration ID

5. **Idempotency** — check for existing declaration by idempotency key before writing; return `200` if already exists

6. **Debezium connector** — `infra/debezium/connector-declare.json` registered against `postgres-declare.outbox`

7. **Unit tests**
   - Idempotency logic
   - Outbox payload serialisation

8. **Component tests** (`@QuarkusTest` + Testcontainers)
   - POST valid declaration → `declarations` row + `outbox` row persisted
   - Duplicate idempotency key → single row, `200` response

### Acceptance criteria
- `POST /declarations` writes both rows atomically
- Debezium connector picks up the outbox row and publishes to `declarations.created`

---

## Phase 4 — Validate Service

**Goal:** Consume `declarations.created`, apply Drools rules, persist result, write outbox.

### Tasks

1. **Scaffold** — same extensions as Declare, plus:
   - `quarkus-drools` (or `drools-quarkus-unit`)
   - `quarkus-smallrye-reactive-messaging-kafka`

2. **Domain model**
   - `Validation` entity
   - `Customer` entity (reference table, seeded at startup)
   - `OutboxEntry` entity (shared schema)

3. **Database migrations**
   - `V1__create_customers.sql` (with seed data)
   - `V2__create_validations.sql`
   - `V3__create_outbox.sql`

4. **Drools rule** — `src/main/resources/rules/validate-customer.drl`
   - Single rule: "Customer must exist" (as specified in PRD §4.2)
   - Rule session must be injectable and mockable

5. **Kafka consumer** — `@Incoming("declarations-created")`
   - Deserialise event
   - Idempotency check on `eventId`
   - Run Drools `KieSession`
   - Write `Validation` + `OutboxEntry` atomically

6. **Kafka config** — consumer group `svc-validate`, topic `declarations.created`

7. **Debezium connector** — `connector-validate.json` for `postgres-validate.outbox`

8. **Unit tests**
   - Drools rule: known customer → PASSED; unknown customer → FAILED with reason
   - Idempotency deduplication logic
   - Event-to-outbox-payload mapping

9. **Component tests**
   - Known `customerId` → `PASSED` outbox row written
   - Unknown `customerId` → `FAILED` outbox row with `failureReason` populated
   - Duplicate `eventId` → idempotent (single row)

### Acceptance criteria
- Drools rule fires correctly for both outcomes
- Downstream `validations.completed` topic receives events via Debezium

---

## Phase 5 — Risk Service ✓ COMPLETE

**Goal:** Consume `validations.completed` (PASSED only), write stub risk record and outbox.

### Tasks

1. **Scaffold** — Declare-like extensions + SmallRye Reactive Messaging

2. **Domain model**
   - `RiskAssessment` entity (`id`, `declarationId`, `validationId`, `score`, `band`, `assessedAt`)
   - `OutboxEntry` entity

3. **Database migrations**
   - `V1__create_risk_assessments.sql`
   - `V2__create_outbox.sql`

4. **Kafka consumer** — `@Incoming("validations-completed")`
   - Discard (acknowledge without processing) events where `outcome = FAILED`
   - Idempotency check on `eventId`
   - Write `RiskAssessment` (stub: `score = 0.0`, `band = LOW`) + `OutboxEntry` atomically

5. **Stub design** — isolate scoring behind a `RiskScorer` interface with a `StubRiskScorer` implementation; wired via CDI so real logic can be dropped in without structural changes

6. **Debezium connector** — `connector-risk.json` for `postgres-risk.outbox`

7. **Unit tests**
   - Stub scorer returns correct constants
   - FAILED input → no record written
   - Idempotency logic

8. **Component tests**
   - PASSED event → `risk_assessments` row with `band=LOW`, `score=0.0` + outbox row
   - FAILED event → no rows written
   - Duplicate event → idempotent

### Acceptance criteria
- Risk service ignores FAILED validations silently
- `risk.assessed` topic receives events for all PASSED validations via Debezium

---

## Phase 6 — Audit Service ✓ COMPLETE

**Goal:** Passive observer. Consume all three topics, write immutable audit log entries.

### Tasks

1. **Scaffold** — PostgreSQL + Flyway + SmallRye Reactive Messaging (no REST, no Drools)

2. **Domain model**
   - `AuditEntry` entity (`id`, `eventId` unique, `topic`, `eventType`, `aggregateId`, `rawPayload`, `receivedAt`)

3. **Database migrations**
   - `V1__create_audit_log.sql` (with unique constraint on `event_id`)

4. **Kafka consumers** — three separate `@Incoming` channels:
   - `audit-declarations` → `declarations.created`
   - `audit-validations` → `validations.completed`
   - `audit-risk` → `risk.assessed`

5. **Idempotency** — ON CONFLICT DO NOTHING on `event_id` unique constraint (database-enforced)

6. **No HTTP API** — Quarkus health endpoint (`/q/health`) only

7. **Unit tests**
   - Deduplication (duplicate `eventId` → single row)
   - Payload preservation (raw JSON stored unchanged)

8. **Component tests**
   - Event on each topic → correct audit row written
   - Duplicate event on any topic → single audit row

### Acceptance criteria
- All three topics produce audit log rows
- No duplicate entries for replayed events

---

## Phase 7 — End-to-End Integration Tests ✓ COMPLETE

**Goal:** Prove the full wiring across all four services and Debezium.

### Tasks

1. **Test harness** — Docker Compose profile that starts all services plus infrastructure

2. **Awaitility** — all async assertions poll with 10s max timeout

3. **Test scenarios** (3 tests as per PRD §9.3):

   | Test | Trigger | Assertions |
   |------|---------|-----------|
   | Happy path | POST declaration with known `customerId` | Audit log has 3 entries; Risk DB has `band=LOW` |
   | Validation failure | POST declaration with unknown `customerId` | Audit log has 2 entries; no `risk.assessed` event; Risk DB empty |
   | Duplicate declaration | POST same declaration twice | Single row in Declare DB; single entry per event in Audit |

4. **CI pipeline** — integration tests run as a separate Maven profile (`-Pit`) so they don't block the standard build

### Acceptance criteria
- All 3 integration tests pass against the full Docker Compose stack
- Total end-to-end latency (Declare → Risk assessed) < 2s under light load

---

## Phase 8 — Native Image Build & Container Packaging ✓ COMPLETE

**Goal:** Each service builds as a GraalVM native image < 100MB.

### Tasks

1. **Dockerfile** (per service) — multi-stage build:
   - Stage 1: GraalVM CE + `native-image` to compile the Quarkus native binary
   - Stage 2: Minimal base image (`ubi-minimal` or `distroless`) with the binary

2. **Quarkus native config** — `quarkus.package.type=native` in `application.properties`
   - Add reflection config for any Jackson-serialised classes
   - Verify Drools native image compatibility (Validate service may require additional config)

3. **Image size check** — assert each image is < 100MB as part of the build

4. **Smoke test** — run `docker compose up` with native images; verify health endpoints respond

### Acceptance criteria
- All four services produce native images < 100MB
- Full stack starts and integration tests pass against native images

---

## Phase 9 — Observability ✓ COMPLETE

**Goal:** Structured JSON logs + Quarkus health endpoints on every service.

### Tasks

1. **Structured logging** — add `quarkus-logging-json` extension to all services; verify log output is valid JSON

2. **Health endpoints** — confirm `/q/health`, `/q/health/live`, `/q/health/ready` respond on all services

3. **Log fields** — ensure `eventId`, `declarationId`, `outcome` are included in log lines for key operations to support correlation across services

### Acceptance criteria
- All services emit JSON logs
- All health endpoints return `UP` in the running stack

---

## Open Items Before Starting

| # | Item | Action |
|---|------|--------|
| 1 | Kafka client library | Write ADR-001 (Phase 2) |
| 2 | Local dev runtime (JVM vs native) | Decide before Phase 8; does not block Phases 1–7 |
| 3 | Quarkus version | Pin a specific version in a parent POM before scaffolding any service |

---

## Phase Sequence Summary

```
Phase 1  Infrastructure skeleton (Docker Compose)
Phase 2  ADR: Kafka client library
Phase 3  Declare service
Phase 4  Validate service
Phase 5  Risk service
Phase 6  Audit service
Phase 7  End-to-end integration tests
Phase 8  Native image build & packaging
Phase 9  Observability
```

Phases 3–6 are largely independent once Phase 1 is stable; they can be parallelised across engineers.
