# Product Requirements Document
## Event-Driven Microservices Platform (PoC)

  
**Status:** Draft  
**Audience:** Internal Engineering  
**Date:** 2026-06-22

---

## 1. Overview

This document defines the requirements for a proof-of-concept event-driven microservices platform. The system is composed of four Quarkus-based microservices — **Declare**, **Validate**, **Risk**, and **Audit** — communicating asynchronously via Apache Kafka. Each service owns its own PostgreSQL database. Transactional integrity between database writes and Kafka message publication is achieved using the **Outbox Pattern** via Debezium CDC (Change Data Capture). All services are containerised and built as GraalVM native images for deployment.

The PoC goal is to validate the architecture, tooling choices, and inter-service communication patterns before committing to a production build.

---

## 2. Goals & Non-Goals

### Goals

- Validate Quarkus as a runtime for containerised, event-driven microservices
- Prove the Outbox Pattern with Debezium guarantees at-least-once, ordered message delivery without distributed transactions
- Demonstrate a clean linear event flow: Declare → Validate → Risk, with Audit as a passive observer of all inbound events
- Establish baseline patterns for service structure, Kafka topic naming, schema design, and observability that future services can adopt

### Non-Goals

- Production-grade SLAs, SLOs, or uptime targets
- External-facing APIs or client integrations
- Authentication, authorisation, or multi-tenancy
- UI or dashboard of any kind
- Horizontal auto-scaling (containerisation is a prerequisite, not a requirement to demonstrate scaling under load)
- Audit query API (direct DB access is sufficient for the PoC)

---

## 3. Architecture Summary

### 3.1 Services

| Service | Responsibility | Publishes to | Consumes from |
|---------|---------------|--------------|---------------|
| **Declare** | Accepts and persists new declarations | `declarations.created` | — (entry point) |
| **Validate** | Validates a declaration against business rules using Drools | `validations.completed` | `declarations.created` |
| **Risk** | Stub risk assessor — always returns `PASSED` for PoC | `risk.assessed` | `validations.completed` |
| **Audit** | Records every inbound event for traceability | — (read-only) | `declarations.created`, `validations.completed`, `risk.assessed` |

### 3.2 Event Flow

```
[External trigger / internal API]
          │
          ▼
      DECLARE ──── outbox ──▶ Kafka: declarations.created
                                       │
                               ┌───────┴──────────┐
                               ▼                  ▼
                          VALIDATE             AUDIT
                          outbox ──▶ Kafka: validations.completed
                                               │
                                    ┌──────────┴──────────┐
                                    ▼                     ▼
                                  RISK                 AUDIT
                                  outbox ──▶ Kafka: risk.assessed
                                                         │
                                                         ▼
                                                      AUDIT
```

Audit subscribes to all three topics independently. It does not participate in the processing chain and never publishes.

### 3.3 Technology Stack

| Concern | Choice |
|---------|--------|
| Service runtime | Quarkus |
| Language | Java 21 |
| Rules engine (Validate) | Drools |
| Message format | Plain JSON |
| Database | PostgreSQL 16 (one instance per service) |
| Messaging | Apache Kafka |
| CDC / Outbox | Debezium (PostgreSQL connector) |
| Containerisation | Docker / OCI-compatible runtime |
| Container image | GraalVM native image |
| Orchestration (PoC) | Docker Compose |
| Kafka client library | Consistent across all services — to be decided via ADR |

> **Note on local development:** Native image compilation is mandated for deployment containers. Local development runtime mode (JVM vs native) is a separate decision not in scope for this document.

---

## 4. Service Specifications

### 4.1 Declare

**Purpose:** The entry point to the system. Accepts a declaration payload, persists it to its own database, and writes an outbox record in the same transaction. Debezium picks up the outbox record and publishes it to Kafka.

**Key behaviours:**
- Exposes an HTTP endpoint (REST) to receive new declarations
- Writes declaration + outbox record atomically in a single DB transaction
- Does not call any other service directly
- Idempotency key on inbound requests to prevent duplicate declarations

**Database schema (minimum):**

`declarations` table: `id` (UUID PK), `customer_id` (UUID), `payload` (JSONB), `status`, `created_at`  
`outbox` table: `id` (UUID PK), `aggregate_type`, `aggregate_id`, `event_type`, `payload` (JSONB), `created_at`

**Published event:** `declarations.created`

```json
{
  "eventId": "uuid",
  "eventType": "declarations.created",
  "aggregateId": "uuid",
  "occurredAt": "ISO-8601",
  "payload": {
    "declarationId": "uuid",
    "customerId": "uuid",
    ...
  }
}
```

---

### 4.2 Validate

**Purpose:** Consumes `declarations.created` events, applies Drools-based validation rules, persists the result, and publishes a `validations.completed` event via its own outbox.

**Key behaviours:**
- Consumes from `declarations.created` Kafka topic (consumer group: `svc-validate`)
- Applies validation rules via a Drools rule engine (`.drl` rules file)
- **PoC validation rule:** checks that the `customerId` on the declaration exists in the Validate service's own customer reference table
- Publishes both `PASSED` and `FAILED` outcomes — Risk only acts on `PASSED`
- Writes validation result + outbox record atomically
- Idempotent on re-delivery (deduplication by `eventId`)

**Drools rule (PoC):**

```
rule "Customer must exist"
  when
    $d : Declaration(customerId != null)
    not Customer(id == $d.customerId)
  then
    $d.setOutcome(Outcome.FAILED);
    $d.setFailureReason("Customer not found: " + $d.getCustomerId());
end
```

**Database schema (minimum):**

`customers` table: `id` (UUID PK) — reference data seeded at startup  
`validations` table: `id` (UUID PK), `declaration_id` (UUID), `outcome` (PASSED/FAILED), `failure_reason` (text, nullable), `rules_applied` (JSONB), `validated_at`  
`outbox` table: standard outbox schema as above

**Published event:** `validations.completed`

```json
{
  "eventId": "uuid",
  "eventType": "validations.completed",
  "aggregateId": "uuid",
  "declarationId": "uuid",
  "outcome": "PASSED | FAILED",
  "failureReason": "Customer not found: <id> | null",
  "occurredAt": "ISO-8601",
  "payload": { ... }
}
```

---

### 4.3 Risk

**Purpose:** Consumes `validations.completed` events where `outcome = PASSED` and publishes a `risk.assessed` event via its own outbox. For the PoC this is a stub implementation — all assessments return `PASSED` with a fixed band of `LOW`.

**Key behaviours:**
- Consumes from `validations.completed` topic (consumer group: `svc-risk`)
- Ignores (acknowledges and discards) events where `outcome = FAILED`
- **PoC stub:** always emits `band = LOW`, `score = 0.0` — no scoring logic implemented
- Writes risk record + outbox record atomically
- Idempotent on re-delivery
- Stub implementation must be designed so real scoring logic can be dropped in without structural changes

**Database schema (minimum):**

`risk_assessments` table: `id` (UUID PK), `declaration_id` (UUID), `validation_id` (UUID), `score` (numeric), `band` (LOW/MEDIUM/HIGH), `assessed_at`  
`outbox` table: standard outbox schema

**Published event:** `risk.assessed`

```json
{
  "eventId": "uuid",
  "eventType": "risk.assessed",
  "aggregateId": "uuid",
  "declarationId": "uuid",
  "score": 0.0,
  "band": "LOW",
  "occurredAt": "ISO-8601",
  "payload": { ... }
}
```

---

### 4.4 Audit

**Purpose:** A passive observer. Subscribes to all three topics and writes a canonical audit log entry for every event it receives. Never publishes. Never influences processing. No query API — direct database access is sufficient for the PoC.

**Key behaviours:**
- Separate consumer groups per topic: `audit-declarations`, `audit-validations`, `audit-risk`
- Writes each inbound event to the audit log with original payload preserved
- Idempotent — duplicate events produce a single audit record (deduplicate by `eventId`)
- Read-only in terms of the processing chain — no outbox, no publications
- Audit records are immutable once written
- No HTTP API exposed

**Database schema (minimum):**

`audit_log` table: `id` (UUID PK), `event_id` (UUID, unique), `topic`, `event_type`, `aggregate_id`, `raw_payload` (JSONB), `received_at`

---

## 5. Outbox Pattern & Debezium

### 5.1 Pattern Overview

Rather than writing to the database and then publishing to Kafka as two separate operations (which risks partial failure), each service writes to its own local `outbox` table inside the same database transaction as the business record. Debezium monitors the `outbox` table via PostgreSQL logical replication (WAL) and publishes the row to Kafka. Outbox rows are **retained** after publication to support replay capability.

### 5.2 Debezium Configuration (per service)

Each service requires a dedicated Debezium PostgreSQL connector configured with:

- `plugin.name`: `pgoutput`
- `table.include.list`: `<schema>.outbox`
- `transforms`: `outbox` (Debezium Outbox Event Router SMT)
- `transforms.outbox.type`: `io.debezium.transforms.outbox.EventRouter`
- `transforms.outbox.table.field.event.id`: `id`
- `transforms.outbox.table.field.event.key`: `aggregate_id`
- `transforms.outbox.table.field.event.payload`: `payload`
- `tombstones.on.delete`: `false`

Each service's Kafka topic is derived from the `aggregate_type` field in the outbox row.

### 5.3 Guarantees

| Property | Behaviour |
|----------|-----------|
| Atomicity | DB write and Kafka publish are atomic via WAL — no dual-write risk |
| Ordering | Debezium preserves WAL order per partition key (`aggregate_id`) |
| Delivery | At-least-once — consumers must be idempotent |
| Exactly-once | Not guaranteed at PoC stage; idempotency keys mitigate duplicates |

---

## 6. Kafka Topics

| Topic | Producer | Consumers | Partition key | Retention |
|-------|----------|-----------|---------------|-----------|
| `declarations.created` | Declare (via Debezium) | Validate, Audit | `declarationId` | 7 days |
| `validations.completed` | Validate (via Debezium) | Risk, Audit | `declarationId` | 7 days |
| `risk.assessed` | Risk (via Debezium) | Audit | `declarationId` | 7 days |

All topics use plain JSON. No Schema Registry or Avro for the PoC. All topics use a single partition for the PoC — partition count can be increased before production without schema changes.

---

## 7. Non-Functional Requirements (PoC Scope)

| Requirement | Target |
|-------------|--------|
| End-to-end latency (Declare → Risk assessed) | < 2 seconds under light load |
| Container image type | GraalVM native image |
| Container image size | < 100MB per service (native) |
| Debezium lag | < 500ms under normal conditions |
| Test coverage | See Section 9 — Testing Strategy |
| Observability | Structured JSON logs; Quarkus health endpoints (`/q/health`) |

---

## 8. Infrastructure (Docker Compose for PoC)

The PoC runs entirely via Docker Compose with the following services:

- `postgres-declare`, `postgres-validate`, `postgres-risk`, `postgres-audit` — four isolated PostgreSQL instances
- `zookeeper` — Kafka coordination
- `kafka` — single-broker Kafka instance
- `debezium-connect` — Kafka Connect with Debezium PostgreSQL connector
- `svc-declare`, `svc-validate`, `svc-risk`, `svc-audit` — the four Quarkus native services

Each PostgreSQL instance must have `wal_level = logical` enabled to support Debezium CDC.

---

## 9. Testing Strategy

The testing approach follows a three-tier pyramid: broad unit test coverage at the base, component tests as the primary functional validation layer, and a small number of high-value end-to-end integration tests at the top.

### 9.1 Unit Tests

**Scope:** Code correctness. Pure logic, no I/O.

Unit tests are fast, isolated, and numerous. Every class with meaningful logic has unit test coverage. External dependencies (database, Kafka, Debezium) are mocked or stubbed.

**What to cover:**

- Drools rules in isolation — each rule fired and not-fired with representative inputs
- Domain model logic — outcome derivation, state transitions, field validation
- Event mapping — declaration/validation/risk domain objects correctly serialised to outbox payload JSON
- Idempotency logic — deduplication logic given a seen vs unseen `eventId`
- Risk stub — correct constant output, correct discard behaviour for `FAILED` input

**Tooling:** JUnit 5, Mockito, Drools unit test API (`KieSession` in-process)

**Coverage target:** All business logic classes. Not required for infrastructure wiring, generated code, or simple getters/setters.

---

### 9.2 Component Tests

**Scope:** Each service behaves correctly end-to-end within its own boundary. This is the primary layer for functional validation.

Component tests run a single service with its real database and real Kafka interaction, using Testcontainers to spin up PostgreSQL and Kafka. Debezium is not included — the outbox table is inspected directly to assert that the correct record was written. No other services are involved.

**What to cover per service:**

| Service | Key component test scenarios |
|---------|------------------------------|
| **Declare** | POST valid declaration → persisted + outbox row written; duplicate `eventId` → idempotent (no duplicate row) |
| **Validate** | Known `customerId` → `PASSED` outcome + outbox row; unknown `customerId` → `FAILED` outcome + `failureReason` populated; duplicate event → idempotent |
| **Risk** | `PASSED` validation event consumed → `risk.assessed` outbox row written with `band=LOW`, `score=0.0`; `FAILED` validation event consumed → no outbox row written; duplicate event → idempotent |
| **Audit** | Event on each topic → audit log row written with correct `topic`, `event_type`, `raw_payload`; duplicate event → single audit row |

**Tooling:** Quarkus `@QuarkusTest`, Testcontainers (PostgreSQL, Kafka), REST Assured for HTTP assertions, direct DB assertions via JDBC

**Notes:**
- Component tests are the authoritative test for each service's functional contract
- Drools rule execution is tested here with real `KieSession` wired to the service (not mocked)
- Tests should seed any required reference data (e.g. the `customers` table in Validate) as part of test setup

---

### 9.3 Integration Tests

**Scope:** The full system, end-to-end. Minimal in number, high in value.

Integration tests run all four services, all four databases, Kafka, and Debezium together via Docker Compose. They exist to prove that the wiring between services works — that Debezium correctly publishes outbox rows to Kafka, and that downstream services consume and process them in order.

**PoC integration test suite (target: 3–5 tests):**

| Test | Scenario | Assertion |
|------|----------|-----------|
| **Happy path** | Submit a declaration with a known `customerId` | Audit log contains three events (`declarations.created`, `validations.completed`, `risk.assessed`) in order; Risk assessment has `band=LOW` |
| **Validation failure** | Submit a declaration with an unknown `customerId` | Audit log contains two events (`declarations.created`, `validations.completed` with `outcome=FAILED`); no `risk.assessed` event; Risk DB has no record |
| **Duplicate declaration** | Submit the same declaration twice (same idempotency key) | Single declaration in Declare DB; single entry per event in Audit log |

**Tooling:** Docker Compose (full stack), REST Assured or plain HTTP client to trigger Declare, direct DB assertions across all four databases, polling with timeout for async assertions (Awaitility)

**Notes:**
- Integration tests are slow by nature — they run in CI but not on every local build
- Async event propagation means assertions must poll with a reasonable timeout (suggested: 10s max)
- These tests do not exhaustively cover business rules — that is the job of component tests
- A failing integration test should point to a wiring or infrastructure problem, not a logic bug

---

### 9.4 Summary

| Layer | Tooling | Runs locally | Runs in CI | Focus |
|-------|---------|:------------:|:----------:|-------|
| Unit | JUnit 5, Mockito, Drools API | Yes — fast | Yes | Logic correctness |
| Component | Quarkus Test, Testcontainers | Yes — moderate | Yes | Per-service functional contract |
| Integration | Docker Compose, Awaitility | Optional | Yes | End-to-end wiring |

---



| # | Question | Decision | Notes |
|---|----------|----------|-------|
| 1 | Kafka message format | **Plain JSON** | No Schema Registry or Avro for PoC |
| 2 | Validate business rule | **Customer ID existence check via Drools** | One `.drl` rule for PoC; engine must be extensible |
| 3 | Risk scoring model | **Stub — always returns `LOW` / `score: 0.0`** | Structure must allow real scoring to be added later |
| 4 | Audit query API | **No API — direct DB access only** | Revisit for production |
| 5 | Container image | **GraalVM native image for deployment** | Local dev runtime to be decided separately |
| 6 | Kafka client library | **Consistent across all services** | Library choice via ADR — flexibility per service only if complexity demands it |
| 7 | Outbox retention | **Retained after publish** | Supports replay; cleanup strategy deferred to production |

---

## 10. Decisions Log

| # | Question | Decision | Notes |
|---|----------|----------|-------|
| 1 | Kafka message format | **Plain JSON** | No Schema Registry or Avro for PoC |
| 2 | Validate business rule | **Customer ID existence check via Drools** | One `.drl` rule for PoC; engine must be extensible |
| 3 | Risk scoring model | **Stub — always returns `LOW` / `score: 0.0`** | Structure must allow real scoring to be added later |
| 4 | Audit query API | **No API — direct DB access only** | Revisit for production |
| 5 | Container image | **GraalVM native image for deployment** | Local dev runtime to be decided separately |
| 6 | Kafka client library | **Consistent across all services** | Library choice via ADR — flexibility per service only if complexity demands it |
| 7 | Outbox retention | **Retained after publish** | Supports replay; cleanup strategy deferred to production |
| 8 | Testing strategy | **Unit + component + minimal integration** | Component tests are primary functional layer; see Section 9 |

---

## 11. Open Questions

| # | Question | Owner | Priority |
|---|----------|-------|----------|
| 1 | Which Kafka client library for Quarkus — SmallRye Reactive Messaging or Kafka Streams? | Engineering | Medium — ADR required |
| 2 | Local development runtime: JVM mode or native image? | Engineering | Low |

---



## 12. Out of Scope (Future Consideration)

- Dead-letter queue (DLQ) handling for poison-pill messages
- Saga / compensating transactions for rollback across services
- Service mesh (e.g. Istio) for mTLS and traffic management
- Multi-region or multi-broker Kafka topology
- Schema evolution strategy
- Audit query API
- Real risk scoring logic beyond the PoC stub
- Additional Drools validation rules beyond customer ID check

---

*Document owner: Engineering PoC Team — v0.3 reflects testing strategy added 2026-06-22.*
