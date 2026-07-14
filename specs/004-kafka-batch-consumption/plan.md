# Implementation Plan: Kafka Batch Record Consumption

**Branch**: `004-kafka-batch-consumption` | **Date**: 2026-07-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-kafka-batch-consumption/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

Enable `svc-validate`, `svc-risk`, and `svc-audit` to consume Kafka records in batches (default 100 per poll, independently configurable per channel) instead of one record at a time, while preserving existing per-message business logic. A record that fails processing is republished immediately (no in-process retry) to a new per-channel dead-letter topic with its original payload and failure details, and the rest of the batch continues processing. Delivery is at-least-once with idempotent per-message processing already assumed safe on redelivery. Batch size and success/dead-letter counts are exposed as Micrometer metrics.

## Technical Context

**Language/Version**: Java 21 (Quarkus 3.15.3 build/runtime requirement; note repository `CLAUDE.md`: tests must run with `JAVA_HOME` pointed at Java 21 due to Byte Buddy incompatibility with Java 25)

**Primary Dependencies**: Quarkus 3.15.3 (`quarkus-smallrye-reactive-messaging-kafka`, `quarkus-hibernate-orm-panache`, `quarkus-jdbc-postgresql`, `quarkus-flyway`, `quarkus-logging-json`), `lib-consumer-map` (internal `ConsumerMapRegistry`), Jackson (`ObjectMapper`); **new**: `quarkus-micrometer-registry-prometheus`

**Storage**: PostgreSQL (one instance per service, unchanged by this feature); Kafka topics (three existing + three new `.dlq` topics)

**Testing**: `quarkus-junit5`, `rest-assured`, `smallrye-reactive-messaging-in-memory` (Kafka channels redirected to in-memory connector under the `%test` profile); run via `mvn test` with `JAVA_HOME` set to Java 21 per project CLAUDE.md

**Target Platform**: Linux containers (Docker Compose for local/CI: `infra/docker-compose.yml`, `infra/docker-compose.ci.yml`)

**Project Type**: Multi-module backend microservices (Maven modules `svc-declare`, `svc-validate`, `svc-risk`, `svc-audit`, shared `lib-consumer-map`, `integration-tests`) — no frontend

**Performance Goals**: Consumer lag on a burst of ≥500 messages returns to zero materially faster than one-at-a-time consumption (SC-001); default batch size of 100 records/poll balances throughput against Kafka consumer session-timeout risk

**Constraints**: No in-process retry before dead-lettering (immediate dead-letter on first failure); at-least-once delivery only (no dedup tracking); topics remain single-partition (existing infra unchanged); no new services or databases

**Scale/Scope**: 3 consuming services, 6 total consumer channels (`declarations-created` in svc-validate; `validations-completed` in svc-risk; `audit-declarations`/`audit-validations`/`audit-risk` in svc-audit), 3 new dead-letter topics

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` in this repository is an unfilled template (no ratified principles, all placeholder text). No project-specific gates are defined to check against. This gate is treated as passing by default; no violations to justify.

## Project Structure

### Documentation (this feature)

```text
specs/004-kafka-batch-consumption/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md         # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   └── kafka-channels.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
svc-validate/
├── src/main/java/uk/co/neversoft/validate/messaging/DeclarationConsumer.java   # change: batch @Incoming
├── src/main/java/uk/co/neversoft/validate/messaging/                          # add: DLQ producer, failure envelope
├── src/main/resources/application.properties                                  # change: batch=true, max.poll.records, DLQ channel, micrometer
└── pom.xml                                                                     # add: quarkus-micrometer-registry-prometheus

svc-risk/
├── src/main/java/uk/co/neversoft/risk/messaging/ValidationConsumer.java        # change: batch @Incoming
├── src/main/java/uk/co/neversoft/risk/messaging/                              # add: DLQ producer, failure envelope
├── src/main/resources/application.properties                                  # change: batch=true, max.poll.records, DLQ channel, micrometer
└── pom.xml                                                                     # add: quarkus-micrometer-registry-prometheus

svc-audit/
├── src/main/java/uk/co/neversoft/audit/messaging/AuditConsumer.java            # change: 3x batch @Incoming methods
├── src/main/java/uk/co/neversoft/audit/messaging/                             # add: DLQ producer, failure envelope
├── src/main/resources/application.properties                                  # change: batch=true, max.poll.records x3, DLQ channels x3, micrometer
└── pom.xml                                                                     # add: quarkus-micrometer-registry-prometheus

lib-consumer-map/    # unchanged — ConsumerMapRegistry.isEnabled() reused as-is, called once per batch

infra/
└── docker-compose.yml   # change: kafka-setup step creates 3 new .dlq topics

integration-tests/
└── src/test/...          # add: batch consumption + partial-failure + DLQ integration tests
```

**Structure Decision**: No new modules or services. Changes are confined to the messaging package of each of the three existing consuming services (consumer signature + new DLQ-publishing helper), each service's `application.properties` and `pom.xml`, the shared Docker Compose topic bootstrap, and `integration-tests`. `svc-declare` and `lib-consumer-map` are unchanged (svc-declare is producer-only; the registry's per-channel enable check is reused unmodified at the batch level).

## Complexity Tracking

*No constitution violations — table not applicable.*
