---

description: "Task list for Kafka Batch Record Consumption"
---

# Tasks: Kafka Batch Record Consumption

**Input**: Design documents from `/specs/004-kafka-batch-consumption/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/kafka-channels.md, quickstart.md

**Tests**: Included — the spec's User Stories define explicit acceptance/independent-test scenarios (batch throughput, partial-failure isolation, crash recovery, configurability) that require automated coverage; svc-audit's Kafka in-memory test pattern already used in this repo (`smallrye-in-memory` connector under `%test`) is reused throughout.

**Organization**: Tasks are grouped by user story (US1 = batch throughput, US2 = partial failure isolation/DLQ, US3 = configurability/observability) per spec.md priorities.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- File paths are exact, relative to repository root

## Path Conventions

Multi-module Maven project (see plan.md Project Structure): `svc-validate/`, `svc-risk/`, `svc-audit/`, `lib-consumer-map/` (unchanged), `infra/`, `integration-tests/`. Each service module has its own `src/main/java`, `src/main/resources`, `src/test/java`, `pom.xml`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Add the shared dependency and Docker Compose topics all three services and the tests need before any batching code is written.

- [x] T001 [P] Add `quarkus-micrometer-registry-prometheus` dependency to `svc-validate/pom.xml`
- [x] T002 [P] Add `quarkus-micrometer-registry-prometheus` dependency to `svc-risk/pom.xml`
- [x] T003 [P] Add `quarkus-micrometer-registry-prometheus` dependency to `svc-audit/pom.xml`
- [x] T004 Add `declarations.created.dlq`, `validations.completed.dlq`, `risk.assessed.dlq` topic creation (1 partition, `retention.ms=604800000`, `--if-not-exists`) to the `kafka-setup` step in `infra/docker-compose.yml`
- [x] T005 N/A — `infra/docker-compose.ci.yml` is a Dockerfile-override file (JVM vs. native builds), not a separate Kafka topic bootstrap; it references the same `kafka-setup` step from `docker-compose.yml`, so T004 alone covers both.

**Checkpoint**: Dependencies and topics exist; no application code changed yet.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared dead-letter envelope/publishing pattern used identically by all three services. No user story can be correctly implemented without this, since batch consumption (US1) and failure isolation (US2) are intertwined in a single `@Incoming` method per channel — batching without safe per-record failure handling would immediately violate FR-003 the moment a bad message appears.

**⚠️ CRITICAL**: Complete before any user story phase.

- [x] T006 [P] Create `DeadLetterEnvelope` record (originalPayload, originalTopic, failureReason, failedAt, serviceName) in `svc-validate/src/main/java/uk/co/neversoft/validate/messaging/DeadLetterEnvelope.java`
- [x] T007 [P] Create `DeadLetterEnvelope` record in `svc-risk/src/main/java/uk/co/neversoft/risk/messaging/DeadLetterEnvelope.java`
- [x] T008 [P] Create `DeadLetterEnvelope` record in `svc-audit/src/main/java/uk/co/neversoft/audit/messaging/DeadLetterEnvelope.java`
- [x] T009 [P] Add outgoing DLQ channel config for `declarations-created-dlq` (channel→topic `declarations.created.dlq`) to `svc-validate/src/main/resources/application.properties`, including `%test` override to `smallrye-in-memory`
- [x] T010 [P] Add outgoing DLQ channel config for `validations-completed-dlq` (channel→topic `validations.completed.dlq`) to `svc-risk/src/main/resources/application.properties`, including `%test` override to `smallrye-in-memory`
- [x] T011 [P] Add outgoing DLQ channel configs for `audit-declarations-dlq`, `audit-validations-dlq`, `audit-risk-dlq` (→ `declarations.created.dlq`, `validations.completed.dlq`, `risk.assessed.dlq`) to `svc-audit/src/main/resources/application.properties`, including `%test` overrides to `smallrye-in-memory`

**Checkpoint**: Dead-letter envelope type and outgoing DLQ channels are configured in all three services; ready for consumer rewrites.

---

## Phase 3: User Story 1 - Higher-throughput processing under load (Priority: P1) 🎯 MVP

**Goal**: Each of the three services fetches and processes multiple queued Kafka messages per poll cycle (up to a configurable max, default 100) instead of one at a time, preserving existing per-message business logic.

**Independent Test**: Publish a burst of ≥200 well-formed messages to a topic and confirm the consuming service processes them in groups (observable via logs/metrics), with consumer lag draining faster than one-at-a-time baseline.

### Tests for User Story 1

- [x] T012 [P] [US1] Component test: `DeclarationConsumer` processes a batch of multiple valid `Message<String>` payloads in one invocation and acks each individually, in `svc-validate/src/test/java/uk/co/neversoft/validate/component/DeclarationConsumerBatchTest.java` — **deviation**: `mp.messaging.incoming.*.batch=true` is a Kafka-connector-specific feature; the in-memory connector used in tests always delivers single payloads, so batch delivery cannot be exercised through `@Incoming`/`InMemoryConnector.source(...)`. The test instead invokes `DeclarationConsumer.consume(List<Message<String>>)` directly with a hand-built batch, which exercises the same per-record processing/ack logic the Kafka connector would drive at runtime.
- [x] T013 [P] [US1] Component test: `ValidationConsumer` processes a batch of multiple valid messages in one invocation, in `svc-risk/src/test/java/uk/co/neversoft/risk/component/ValidationConsumerBatchTest.java` (same direct-invocation approach as T012)
- [x] T014 [P] [US1] Component test: `AuditConsumer.onDeclaration`/`onValidation`/`onRisk` each process a batch of multiple valid messages in one invocation, in `svc-audit/src/test/java/uk/co/neversoft/audit/component/AuditConsumerBatchTest.java` (same direct-invocation approach as T012, covering all 3 channels)

### Implementation for User Story 1

- [x] T015 [US1] Set `mp.messaging.incoming.declarations-created.batch=true` and `mp.messaging.incoming.declarations-created.max.poll.records=100` in `svc-validate/src/main/resources/application.properties`
- [x] T016 [US1] Set `mp.messaging.incoming.validations-completed.batch=true` and `mp.messaging.incoming.validations-completed.max.poll.records=100` in `svc-risk/src/main/resources/application.properties`
- [x] T017 [US1] Set `batch=true` and `max.poll.records=100` for `audit-declarations`, `audit-validations`, `audit-risk` channels in `svc-audit/src/main/resources/application.properties`
- [x] T018 [US1] Rewrite `DeclarationConsumer.consume` in `svc-validate/src/main/java/uk/co/neversoft/validate/messaging/DeclarationConsumer.java` to accept `List<Message<String>> batch`, check `registry.isEnabled("declarations-created")` once per batch, iterate records calling the existing `ValidationService.validate` per record and `.ack()` each on success (failure handling added in US2)
- [x] T019 [US1] Rewrite `ValidationConsumer.consume` in `svc-risk/src/main/java/uk/co/neversoft/risk/messaging/ValidationConsumer.java` to accept `List<Message<String>> batch`, same per-record iterate+ack pattern
- [x] T020 [US1] Rewrite `AuditConsumer.onDeclaration`, `onValidation`, `onRisk` in `svc-audit/src/main/java/uk/co/neversoft/audit/messaging/AuditConsumer.java` to each accept `List<Message<String>> batch`, same per-record iterate+ack pattern (shared via a private `processBatch` helper)
- [x] T021 [US1] Set commit strategy to `throttled` explicitly for all six consumer channels (`mp.messaging.incoming.<channel>.commit-strategy=throttled`) across `svc-validate`, `svc-risk`, `svc-audit` `application.properties` (per research.md Decision 3)

**Checkpoint**: All three services consume in batches; existing single-message behavior is preserved for well-formed messages. User Story 1 is independently testable and deployable.

---

## Phase 4: User Story 2 - Partial batch failure handling (Priority: P2)

**Goal**: A malformed/unprocessable message within a batch is isolated — republished immediately to a per-channel dead-letter topic with original payload and failure details — without blocking or losing the rest of the batch, and without permanently losing or duplicating work across a mid-batch crash/restart.

**Independent Test**: Inject a batch with 50 valid + 1 malformed message; confirm all 50 process successfully, the malformed one lands on the `.dlq` topic with failure details, and a simulated mid-batch crash/restart does not lose or permanently skip any queued message.

### Tests for User Story 2

- [x] T022 [P] [US2] Component test: a malformed payload in a `declarations-created` batch is dead-lettered with correct `DeadLetterEnvelope` fields, while sibling valid messages in the same batch still succeed — **consolidated into `DeclarationConsumerBatchTest.java`** (`batchWithOneMalformedMessage_isolatesFailureAndDeadLetters`) rather than a separate `DeclarationConsumerDlqTest.java`, since both tests share the same direct-invocation fixture; DLQ assertions still use the real `smallrye-in-memory` sink for `declarations-created-dlq`.
- [x] T023 [P] [US2] Component test: same partial-failure/DLQ behavior for `ValidationConsumer` — consolidated into `svc-risk/src/test/java/uk/co/neversoft/risk/component/ValidationConsumerBatchTest.java` (same rationale as T022)
- [x] T024 [P] [US2] Component test: same partial-failure/DLQ behavior for `AuditConsumer` (declarations channel, representative of all 3) — consolidated into `svc-audit/src/test/java/uk/co/neversoft/audit/component/AuditConsumerBatchTest.java` (same rationale as T022)
- [ ] T025 [US2] **Not run in this pass.** Integration test against the real Docker Compose Kafka stack is out of scope for an automated coding session (needs `docker compose up` with full image builds, native builds take 15-20 min each per `Dockerfile`). Covered manually via `quickstart.md` step 3 instead — run before enabling batching in production.
- [x] T026 [P] [US2] Injected `@Channel("declarations-created-dlq") Emitter<String>` into `DeclarationConsumer`; on a per-record processing exception, builds a `DeadLetterEnvelope` (original payload, topic, exception message, timestamp, `serviceName="svc-validate"`), serializes and sends it, then `.ack()`s the failed record, in `svc-validate/src/main/java/uk/co/neversoft/validate/messaging/DeclarationConsumer.java`
- [x] T027 [P] [US2] Same DLQ-publish-on-failure wiring in `svc-risk/src/main/java/uk/co/neversoft/risk/messaging/ValidationConsumer.java` (serviceName="svc-risk")
- [x] T028 [P] [US2] Same DLQ-publish-on-failure wiring in `svc-audit/src/main/java/uk/co/neversoft/audit/messaging/AuditConsumer.java` for all three channels (serviceName="svc-audit")
- [x] T029 [US2] Confirmed `.ack()` is called only after the dead-letter publish (or successful processing) for each record, using `commit-strategy=throttled` from T021, across all three services' consumer methods

**Checkpoint**: A single bad message no longer blocks or loses a batch; failures are recoverable via the DLQ topics; mid-batch crash recovery is safe. User Stories 1 and 2 together are independently testable and deployable.

---

## Phase 5: User Story 3 - Configurable and observable batch behavior (Priority: P3)

**Goal**: Operators can tune each channel's max batch size via configuration (no code change) and observe batch size and success/failure counts as metrics.

**Independent Test**: Change a channel's `max.poll.records` between test runs and confirm observed batch sizes respect the new limit; confirm `kafka.batch.size`, `kafka.batch.records.success`, `kafka.batch.records.deadlettered` metrics are visible at `/q/metrics` after processing a batch.

### Tests for User Story 3

- [x] T030 [P] [US3] Component test: after processing a batch, the injected `MeterRegistry` shows `kafka.batch.size{channel="declarations-created"}` and `kafka.batch.records.success` counters incremented correctly, in `svc-validate/src/test/java/uk/co/neversoft/validate/component/DeclarationConsumerMetricsTest.java` (direct-invocation approach, same rationale as T012)
- [x] T031 [P] [US3] Equivalent metrics component test for `svc-risk`, in `svc-risk/src/test/java/uk/co/neversoft/risk/component/ValidationConsumerMetricsTest.java`
- [x] T032 [P] [US3] Equivalent metrics component test for `svc-audit`'s declarations channel (representative of all 3), in `svc-audit/src/test/java/uk/co/neversoft/audit/component/AuditConsumerMetricsTest.java`

### Implementation for User Story 3

- [x] T033 [P] [US3] Injected `MeterRegistry` into `DeclarationConsumer`; records a `DistributionSummary` sample of batch size plus `Counter` increments for success/dead-lettered outcomes, tagged `channel=declarations-created`, in `svc-validate/src/main/java/uk/co/neversoft/validate/messaging/DeclarationConsumer.java`
- [x] T034 [P] [US3] Same metrics instrumentation in `svc-risk/src/main/java/uk/co/neversoft/risk/messaging/ValidationConsumer.java`, tagged `channel=validations-completed`
- [x] T035 [P] [US3] Same metrics instrumentation in `svc-audit/src/main/java/uk/co/neversoft/audit/messaging/AuditConsumer.java` for all three methods, each tagged with its own channel name
- [x] T036 [US3] Documented the `max.poll.records` env-var override pattern as a comment in each service's `environment:` block in `infra/docker-compose.yml` (no code/rebuild needed to change it — standard MicroProfile Config env var mapping)

**Checkpoint**: Batch size is tunable per channel without code changes; batch outcomes are visible as metrics. All three user stories are independently functional.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final validation and regression safety net across all three stories.

- [ ] T037 **Not run in this pass.** `quickstart.md` steps 2–5 require the full Docker Compose stack up (`docker compose up --wait`) with built service images — a manual/CI validation step, not run during this automated implementation session.
- [x] T038 [P] Ran full regression suite for all affected modules with `JAVA_HOME` set to the Java 21 install per root `CLAUDE.md`: `svc-validate` 11/11 passing, `svc-risk` 9/9 passing, `svc-audit` 13/13 passing (all via Testcontainers-backed `mvn test`, Docker/Colima started for this run). `integration-tests` confirmed via `test-compile` only — its actual IT suite runs via `mvn verify -Pit` against a live `docker compose` stack, out of scope here (same reason as T037).
- [x] T039 [P] Updated `README.md` with a "Batch Consumption & Dead-Letter Topics" section (default batch size, DLQ topic table, delivery semantics, metrics) and a Key Patterns row pointing to this spec.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately (T001-T003 parallel; T004-T005 independent of T001-T003)
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories (dead-letter envelope type and DLQ channels must exist before any consumer rewrite touches failure handling)
- **User Story 1 (Phase 3)**: Depends on Foundational phase completion
- **User Story 2 (Phase 4)**: Depends on User Story 1's consumer rewrite (T018-T020) being in place, since DLQ publishing is added to the same methods — not independently deployable before US1, but independently testable/verifiable as an increment on top of US1
- **User Story 3 (Phase 5)**: Depends on User Story 1's consumer rewrite (T018-T020) for metrics instrumentation points; independent of US2's DLQ logic (can be developed in parallel with Phase 4 once Phase 3 is done)
- **Polish (Phase 6)**: Depends on all three user stories being complete

### Within Each User Story

- Tests written first (T012-T014, T022-T025, T030-T032), expected to fail before implementation
- Config changes (batch=true, max.poll.records) before consumer code rewrites within US1
- Consumer rewrite (US1) before DLQ wiring (US2) and metrics wiring (US3), since both build on the batch-iteration loop introduced in US1

### Parallel Opportunities

- T001-T003 (pom.xml dependency additions across 3 services) in parallel
- T006-T011 (DeadLetterEnvelope + DLQ channel config across 3 services) in parallel
- T012-T014 (US1 tests across 3 services) in parallel
- T022-T024 (US2 tests across 3 services) in parallel
- T026-T028 (US2 DLQ wiring across 3 services) in parallel
- T030-T032 (US3 tests across 3 services) in parallel
- T033-T035 (US3 metrics wiring across 3 services) in parallel
- Phase 5 (US3) can proceed in parallel with Phase 4 (US2) once Phase 3 is complete, since they touch the same files but different concerns (DLQ vs. metrics) — coordinate to avoid merge conflicts if worked on simultaneously by different people, or sequence T026-T029 before T033-T036 per service if done solo

---

## Parallel Example: User Story 1

```bash
# Launch all US1 tests together:
Task: "Component test: DeclarationConsumer batch processing in svc-validate/src/test/java/uk/co/neversoft/validate/component/DeclarationConsumerBatchTest.java"
Task: "Component test: ValidationConsumer batch processing in svc-risk/src/test/java/uk/co/neversoft/risk/component/ValidationConsumerBatchTest.java"
Task: "Component test: AuditConsumer batch processing in svc-audit/src/test/java/uk/co/neversoft/audit/component/AuditConsumerBatchTest.java"

# Launch all US1 config changes together:
Task: "Set batch=true and max.poll.records=100 in svc-validate/src/main/resources/application.properties"
Task: "Set batch=true and max.poll.records=100 in svc-risk/src/main/resources/application.properties"
Task: "Set batch=true and max.poll.records=100 for 3 channels in svc-audit/src/main/resources/application.properties"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: Run quickstart.md step 2 against Docker Compose; confirm batches >1 observed and lag drains
5. Deploy/demo if ready — note: US1 alone increases blast radius of bad messages (no DLQ yet), so treat MVP as a staging/controlled-rollout milestone, not a production-safe end state, per spec.md's stated priority rationale for US2

### Incremental Delivery

1. Setup + Foundational → dependencies and DLQ topics/types ready
2. Add User Story 1 → test independently → staging validation (batch throughput proven)
3. Add User Story 2 → test independently → production-safe (failure isolation proven) — this is the recommended minimum bar before enabling batching in production
4. Add User Story 3 → test independently → full operational tunability/observability
5. Polish → full regression + quickstart validation

### Parallel Team Strategy

With multiple developers, after Foundational completes:

- Developer A: `svc-validate` changes across US1/US2/US3 (T015, T018, T021, T026, T029, T033, T036 subset)
- Developer B: `svc-risk` changes across US1/US2/US3
- Developer C: `svc-audit` changes across US1/US2/US3 (largest surface — 3 channels)

Each developer owns one service end-to-end since all three services share an identical pattern per story; this avoids cross-service merge conflicts while still following the story-priority order (US1 → US2 → US3) within their own service.

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Tests included per spec.md's explicit acceptance scenarios; write and confirm failing before implementing each phase's tasks
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- US2 and US3 both extend the same consumer methods introduced in US1 — they are logically independent (DLQ vs. metrics) but share files, so coordinate ordering per service if split across developers
