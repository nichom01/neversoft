# Research: Kafka Batch Record Consumption

## Context

Three Quarkus services (`svc-validate`, `svc-risk`, `svc-audit`) each consume JSON-payload Kafka messages one at a time via SmallRye Reactive Messaging (`quarkus-smallrye-reactive-messaging-kafka`, part of the shared Quarkus 3.15.3 BOM, Java 21). Each currently has an `@Incoming("<channel>") void consume(String messageJson)` method. Topics (`declarations.created`, `validations.completed`, `risk.assessed`) are single-partition, created by `infra/docker-compose.yml`'s `kafka-setup` init container with 7-day retention. All three consumers route through the shared `ConsumerMapRegistry` (`lib-consumer-map`) per-channel enable/disable check before doing any work.

## Decision 1: Batch consumption mechanism

**Decision**: Use SmallRye Reactive Messaging Kafka's native batch mode by setting `mp.messaging.incoming.<channel>.batch=true` and changing each `@Incoming` method signature to accept `List<Message<String>>` (unwrapped as `Message` per record, not the raw payload), rather than a raw `List<String>`.

**Rationale**: `batch=true` is a first-class, config-only SmallRye Kafka feature (no manual poll-loop code needed) — the connector fetches up to `max.poll.records` records per Kafka `poll()` and delivers them as one `Message` wrapping a `List`. Using `List<Message<String>>` (rather than `List<String>`) is required to `ack()`/`nack()` individual records, which Story 2 (partial batch failure) depends on. A plain `List<String>` payload would only support acking/nacking the batch as a single unit, which is too coarse for per-record dead-lettering.

**Alternatives considered**:
- Manual `KafkaConsumer` poll loop bypassing Reactive Messaging: rejected — reimplements what the connector already provides, loses Quarkus DevServices/test wiring (`smallrye-in-memory` connector already used in tests), much larger surface area.
- `Multi<Message<String>>` reactive stream per channel with a `.group().intoLists()` operator: functionally similar to `batch=true` but adds an extra manual batching operator; `batch=true` is simpler and is SmallRye's documented mechanism for this exact use case.

## Decision 2: Per-record failure isolation and dead-lettering

**Decision**: Do NOT rely on the SmallRye Kafka connector's built-in `failure-strategy=dead-letter-queue`, because in batch mode failure/ack strategy applies to the `Message<List<...>>` wrapper as a whole, not to individual list elements. Instead, iterate the unwrapped `List<Message<String>>` inside the `@Incoming` method, `try`/`catch` around each record's existing per-message processing call, and on failure explicitly publish the raw payload + failure metadata to a `@Channel`-injected Kafka producer targeting a new per-service dead-letter topic (e.g. `declarations.created.dlq`), then call `.nack()` (or `.ack()` after successful dead-letter publish, per Decision 3) on that record's `Message`. Successful records call `.ack()` individually.

**Rationale**: This matches the clarified requirement (FR-003, FR-004): isolate failures to the offending record, keep processing the rest of the batch, and republish only the failed record — with its original content and failure details — to a dead-letter topic, immediately, with no in-process retry.

**Alternatives considered**:
- Built-in connector DLQ (`failure-strategy=dead-letter-queue`): rejected as noted above — operates at the batch-message level in batch mode, would dead-letter the entire batch on one bad record.
- Third-party retry/circuit-breaker library (e.g., SmallRye Fault Tolerance `@Retry`): rejected — clarified answer is immediate dead-letter with no retry, so retry tooling is unnecessary.

## Decision 3: Batch acknowledgment / offset commit strategy and crash recovery

**Decision**: Use the default `throttled` commit strategy (SmallRye Kafka's default for non-batch and compatible with batch mode when each record is acked individually), so the consumed offset only advances up to the highest contiguously-acked record. Every record in a batch — whether processed successfully or dead-lettered — must be `ack()`'d once its outcome (success or DLQ publish) is durable; only unacked records (i.e., a crash between poll and ack/DLQ-publish) are redelivered on restart.

**Rationale**: This directly satisfies FR-005/FR-006/FR-011 and the clarified at-least-once/idempotent-processing answer: on a mid-batch crash, only records that were neither fully processed nor dead-lettered remain unacked and are redelivered; already-handled records (success or DLQ) are not lost or reprocessed as failures, while some benign duplicate redelivery is possible and accepted (each service's processing must already be safe to repeat).

**Alternatives considered**:
- Manual/explicit offset commit disabled entirely (`enable.auto.commit=false` with no SmallRye commit strategy) and hand-rolled offset tracking: rejected — reimplements what `throttled` already provides.
- `latest` commit strategy (commit the highest offset seen regardless of ack order): rejected — could commit past an unacked record on crash, causing permanent skip, which violates FR-006.

## Decision 4: Batch size configuration

**Decision**: Configure per-channel `mp.messaging.incoming.<channel>.max.poll.records=<N>` (standard Kafka consumer config, honoured by the SmallRye connector), defaulting to `100` per the clarified answer, independently settable per channel via `application.properties` / environment variable overrides — already the project's existing config mechanism (`%prod.` profile overrides, env var mapping via MicroProfile Config). No code change is needed to retune it.

**Rationale**: `max.poll.records` is the standard Kafka client mechanism controlling how many records a single `poll()` returns, which SmallRye's `batch=true` mode uses directly to size each delivered batch. Reusing it avoids inventing a project-specific batch-size setting.

**Alternatives considered**: A custom `<channel>.batch-size` property read manually in code — rejected, redundant with the standard Kafka client setting already wired through MicroProfile Config.

## Decision 5: Observability (batch size, success/failure counts)

**Decision**: Add the `quarkus-micrometer-registry-prometheus` extension (available in the project's Quarkus 3.15.3 BOM, not yet used by any of the three services) to each of `svc-validate`, `svc-risk`, `svc-audit`, and record, per processed batch: a `DistributionSummary` (or simple `Counter`) for batch size, and `Counter`s for per-record success/failure/dead-lettered outcomes, tagged by channel/topic.

**Rationale**: Matches the clarified answer (structured metrics via a Micrometer-style registry) and is additive — no existing service currently depends on or conflicts with Micrometer, and Prometheus-format metrics are the conventional Quarkus default that any existing scrape-based monitoring can pick up without further plumbing decisions.

**Alternatives considered**: Emitting batch stats only as structured JSON log lines (the services already have `quarkus-logging-json` configured) — rejected per the clarified answer, which explicitly called for metrics rather than logs-only.

## Decision 6: Dead-letter topic provisioning

**Decision**: Add one dead-letter topic per consumed channel to the `kafka-setup` init container in `infra/docker-compose.yml` (e.g. `declarations.created.dlq`, `validations.completed.dlq`, `risk.assessed.dlq`), single partition, same retention as the source topics, created alongside the existing three topics.

**Rationale**: Keeps topic provisioning consistent with how the existing three topics are already bootstrapped for local/CI environments; no new infrastructure component, per the spec's Assumptions ("No new infrastructure ... required").

**Alternatives considered**: A single shared DLQ topic across all services — rejected; per-service/per-channel DLQ topics keep failure investigation scoped and match FR-004's "per-service dead-letter topic" wording, and match svc-audit's three independent channels (FR-009).

## Open questions

None remaining — all Technical Context unknowns are resolved above and all spec clarifications are already captured in `spec.md`.
