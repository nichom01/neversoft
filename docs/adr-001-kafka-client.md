# ADR-001: Kafka Client Library for Quarkus Services

**Status:** Accepted  
**Date:** 2026-06-22  
**Deciders:** Engineering PoC Team

---

## Context

All four services (Declare, Validate, Risk, Audit) need to produce or consume Kafka messages. A single library must be chosen and used consistently across all services (Decision Log #6 in the PRD).

Two credible options exist in the Quarkus ecosystem:

| Option | Description |
|--------|-------------|
| **SmallRye Reactive Messaging** (`quarkus-smallrye-reactive-messaging-kafka`) | Declarative `@Incoming` / `@Outgoing` annotations; reactive pipeline model; first-class Quarkus extension |
| **Kafka Streams** (`quarkus-kafka-streams`) | Stateful stream processing DSL; topology-based; suited to aggregation and windowing |

---

## Decision

**SmallRye Reactive Messaging** is chosen as the Kafka client library for all services.

---

## Rationale

The PoC services are simple point-to-point event consumers and producers:

- **Declare** produces to one topic via the Outbox pattern (Debezium handles publication — no direct Kafka producer needed in service code)
- **Validate**, **Risk**, and **Audit** each consume from one or two topics and produce to one topic (or none, in Audit's case)

None of the services require stateful aggregation, windowing, or stream joins — the defining use cases for Kafka Streams. Introducing Kafka Streams for simple consume/transform/produce pipelines adds:

- A mandatory application-id and internal changelog topics
- A topology builder abstraction that is heavier than the task requires
- More complex native image configuration

SmallRye Reactive Messaging fits directly:

- `@Incoming("topic-name")` and `@Outgoing("topic-name")` are low-ceremony and readable
- Native image support is first-class via the Quarkus extension
- Consumer group configuration (`mp.messaging.incoming.<channel>.group.id`) maps directly to the per-service group IDs in the PRD
- The reactive pipeline model handles back-pressure without boilerplate

---

## Consequences

- All four services add `quarkus-smallrye-reactive-messaging-kafka` as a Maven dependency
- Kafka channel config lives in each service's `application.properties` under `mp.messaging.*`
- Consumer group IDs are set per service:
  - `validate-svc` on `declarations.created`
  - `risk-svc` on `validations.completed`
  - `audit-declarations`, `audit-validations`, `audit-risk` on their respective topics
- If a future service requires stateful aggregation, Kafka Streams may be introduced for that service only — this ADR does not preclude mixed usage in future

---

## Alternatives Rejected

**Kafka Streams** — ruled out because none of the PoC services perform stateful operations. The overhead of the streams topology model is unjustified for simple consume/produce pipelines.

**Raw Kafka client** (`kafka-clients`) — ruled out because it requires manual consumer loop management, offset handling, and deserialization wiring that Quarkus extensions already handle correctly and safely.
