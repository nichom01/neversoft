# ADR-002: Debezium and the Outbox Pattern for Atomic Event Publication

**Status:** Accepted  
**Date:** 2026-06-23  
**Deciders:** Engineering PoC Team

---

## Context

Three services — `svc-declare`, `svc-validate`, and `svc-risk` — must publish a Kafka event every time they write a domain record. The naive approach is a dual-write: persist to PostgreSQL, then produce directly to Kafka within the same request handler. Dual-write has a fundamental atomicity problem: if the Kafka produce call fails after the database commit (or the process crashes in between), the event is silently lost. If the database commit fails after the Kafka produce succeeds, a spurious event is published. Neither failure mode is recoverable without external coordination.

Two credible approaches exist for solving this:

| Option | Description |
|--------|-------------|
| **Transactional Outbox + CDC** | Write the event into an `outbox` table in the same database transaction as the domain record. A separate process (Debezium) reads the database Write Ahead Log (WAL) and publishes to Kafka. |
| **Kafka Transactions (exactly-once semantics)** | Use a Kafka transactional producer with idempotent delivery. Requires a two-phase commit or saga to keep the database and Kafka in sync; there is no standard mechanism to span a JDBC transaction and a Kafka transaction atomically. |

---

## Decision

The **Transactional Outbox pattern** is adopted for all services that need to publish domain events. **Debezium** (PostgreSQL connector via logical replication / `pgoutput`) is the CDC relay that reads the outbox table and forwards events to Kafka.

Each producing service (`svc-declare`, `svc-validate`, `svc-risk`) maintains its own `public.outbox` table in its dedicated PostgreSQL instance. A dedicated Debezium connector is registered per service. The Debezium `EventRouter` SMT routes each outbox row to a Kafka topic named by the `aggregate_type` column.

---

## Rationale

### Atomicity without distributed transactions

Writing the domain entity and the outbox row in a single `@Transactional` method delegates atomicity entirely to PostgreSQL. Either both writes commit or neither does. There is no window where the domain state is visible but the event has not been queued.

```java
// svc-declare: DeclarationService.create()
@Transactional
public DeclarationResult create(CreateDeclarationRequest request) {
    declaration.persist();   // domain write
    outbox.persist();        // event write — same transaction
}
```

### WAL as a reliable event queue

Debezium reads the PostgreSQL WAL via logical replication, which provides an ordered, durable, at-least-once stream of committed changes. The connector only advances its replication slot after a row has been successfully published to Kafka, so a connector restart replays from the last confirmed offset — no events are dropped.

### Decoupled publication

Services have no Kafka producer dependency in their application code. The publication concern is handled entirely in infrastructure (Debezium + Kafka Connect). This simplifies service code, eliminates Kafka client configuration from each service, and means a Kafka outage does not cause service request failures — the outbox table absorbs the backpressure until the connector recovers.

### Topic routing via `aggregate_type`

The `EventRouter` SMT uses the `aggregate_type` column to determine the target topic, so a single connector per service handles multiple event types without configuration changes. New event types require only a new `aggregate_type` value; no connector reconfiguration is needed.

| Field | Purpose |
|-------|---------|
| `id` | Unique event ID (deduplication key for consumers) |
| `aggregate_type` | Routes to Kafka topic (e.g. `declarations.created`) |
| `aggregate_id` | Kafka message key; enables per-aggregate ordering |
| `event_type` | Carried in the payload for consumer filtering |
| `payload` | Full event payload as JSONB |
| `created_at` | Event time recorded at write time |

---

## Consequences

- Each producing service database must have logical replication enabled (`wal_level = logical`).
- A Debezium Kafka Connect cluster is a required infrastructure dependency. Its availability is in the event publication path but not the request path.
- Outbox rows are not deleted by the service; Debezium's replication slot is the source of ordering truth. Outbox table housekeeping (deletion of processed rows) must be handled by a separate cleanup job to prevent unbounded table growth.
- Consumers must be idempotent. Debezium provides at-least-once delivery; a connector restart after a partial publish can re-deliver the same row.
- `snapshot.mode: never` is set on all connectors — only changes made after connector registration are captured. Historical outbox rows are not replayed on connector startup.
- `tombstones.on.delete: false` suppresses Kafka tombstone messages when outbox rows are deleted, keeping the topic clean for consumers that do not expect tombstones.
- The `aggregate_id` column is used as the Kafka message key, which guarantees ordering of events for the same aggregate within a partition.

---

## Alternatives Rejected

**Dual-write (direct Kafka producer in service code)** — ruled out because it cannot guarantee atomicity. A crash or network failure between the database commit and the Kafka produce creates an irrecoverable inconsistency with no reliable detection mechanism.

**Kafka exactly-once semantics with transactional producer** — ruled out because there is no standard protocol for spanning a JDBC transaction and a Kafka transaction atomically. Achieving this requires a saga or two-phase commit coordinator, which adds significant complexity unjustified at PoC scale.

**Spring / Debezium Embedded Engine** — ruled out because it couples the CDC process lifecycle to the service process. A service restart would interrupt event publication, and a service crash would delay publication until the service recovers. Running Debezium as a standalone Kafka Connect worker decouples these concerns.
