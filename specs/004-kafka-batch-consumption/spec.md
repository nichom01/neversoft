# Feature Specification: Kafka Batch Record Consumption

**Feature Branch**: `004-kafka-batch-consumption`

**Created**: 2026-07-14

**Status**: Draft

**Input**: User description: "Enable batch consumption of Kafka records in svc-validate, svc-risk, and svc-audit so each service can process multiple messages per poll instead of one at a time."

## Clarifications

### Session 2026-07-14

- Q: When a message in a batch fails to process (malformed payload or downstream error), how should the system handle that failed message? → A: Dead-letter topic — failed messages are republished to a per-service dead-letter topic for later inspection/reprocessing
- Q: After a mid-batch crash/restart, redelivered messages may be reprocessed. What delivery/dedup guarantee should the system provide? → A: At-least-once with idempotent processing — duplicate redelivery is expected and acceptable; each service's own processing logic must tolerate reprocessing the same message without corrupting state
- Q: How should batch size and success/failure counts be exposed for observability (FR-008)? → A: Structured metrics — batch size and success/failure counts are emitted as metrics (e.g., Micrometer counters/gauges) queryable via the services' existing metrics backend
- Q: What should the default maximum batch size be at launch (before any operator tuning)? → A: Moderate default (~100) — meaningful throughput improvement while staying safely within typical session-timeout windows
- Q: Should a failed message be retried in-process before being dead-lettered, or sent to the dead-letter topic immediately on first failure? → A: Immediate dead-letter — a message that fails processing is sent to the dead-letter topic on first failure, no retries

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Higher-throughput processing under load (Priority: P1)

As an operator of the declaration processing pipeline, when message volume on `declarations.created`, `validations.completed`, or `risk.assessed` spikes, I want each downstream service to pull and process multiple queued messages together per poll cycle, so that the pipeline keeps pace with incoming volume instead of falling behind one message at a time.

**Why this priority**: This is the core value of the feature — without batch pull-and-process, none of the throughput or efficiency benefits exist. Every other behavior in this spec depends on this working correctly.

**Independent Test**: Publish a burst of messages (e.g., 200) to a topic consumed by one of the three services in a test environment and confirm the service fetches and processes them in groups rather than strictly one-by-one, with total processing time noticeably lower than sequential single-record handling.

**Acceptance Scenarios**:

1. **Given** a topic has multiple unconsumed messages queued, **When** the consuming service polls, **Then** it retrieves and processes more than one message in that processing cycle (up to the configured batch size).
2. **Given** a burst of messages arrives faster than single-record processing could keep up, **When** the service is running under normal conditions, **Then** consumer lag stabilizes or decreases rather than growing unbounded.
3. **Given** fewer messages are available than the configured batch size, **When** the service polls, **Then** it processes whatever is available without waiting indefinitely for the batch to fill.

---

### User Story 2 - Partial batch failure handling (Priority: P2)

As an operator, when a batch of messages contains one or more malformed or unprocessable records, I want the service to isolate the failure to the affected record(s) and continue processing the rest of the batch, so that a single bad message does not stall or lose an entire batch of otherwise-valid work.

**Why this priority**: Batch consumption increases blast radius of a single bad message — without correct isolation, one malformed record could block or lose many valid records at once, which is worse than today's one-at-a-time behavior. This must work before batch consumption is considered safe to enable in production.

**Independent Test**: Inject a batch containing a mix of valid and deliberately malformed messages (e.g., invalid JSON) into a test topic and confirm all valid messages are still processed and only the malformed message(s) are rejected/logged, with no valid message lost or duplicated.

**Acceptance Scenarios**:

1. **Given** a batch contains one malformed message among otherwise valid messages, **When** the batch is processed, **Then** all valid messages are processed successfully and the malformed message is reported as failed without stopping the rest of the batch.
2. **Given** a batch processing attempt fails partway through, **When** the service recovers, **Then** already-successfully-processed messages in that batch are not reprocessed and unprocessed messages in the batch are retried or surfaced for handling.

---

### User Story 3 - Configurable and observable batch behavior (Priority: P3)

As an operator, I want to be able to tune how many records each service pulls per batch and to see batch-level metrics (batch size, processing time, failure counts), so that I can adjust the pipeline for different load conditions and diagnose issues quickly.

**Why this priority**: Useful for operating and tuning the system once batching works correctly, but the pipeline delivers its core value (P1) and safety (P2) without configurability or dedicated batch metrics — this is an enhancement on top of a working batch mechanism.

**Independent Test**: Change the configured batch size for one service between test runs and confirm the observed batch sizes in logs/metrics reflect the new configuration; confirm batch-related metrics are visible via the service's existing observability tooling.

**Acceptance Scenarios**:

1. **Given** an operator changes a service's configured maximum batch size, **When** the service restarts and consumes new messages, **Then** it fetches batches no larger than the newly configured maximum.
2. **Given** a service has processed one or more batches, **When** an operator inspects the service's metrics, **Then** they can see batch size and batch processing outcome (success/failure counts) for recent batches.

---

### Edge Cases

- What happens when a batch is only partially consumed before the service is shut down or restarted (e.g., mid-batch crash)? Unprocessed messages in that batch must not be silently lost and must not require manual intervention to recover.
- How does the system behave when the configured batch size is larger than the number of messages ever likely to be queued (i.e., batches are almost always small)? Processing should proceed without unnecessary delay.
- How does the system handle a message batch spanning multiple partitions or produced by multiple upstream producers, where ordering guarantees might differ from single-record consumption?
- What happens if downstream processing of a batch takes long enough to risk a consumer group rebalance or session timeout mid-batch?
- Duplicate deliveries are expected if a batch is redelivered after a partial-failure recovery (per User Story 2); each service's processing logic must be idempotent so reprocessing a duplicate message does not corrupt state or produce duplicate downstream effects.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Each of svc-validate, svc-risk, and svc-audit MUST be able to retrieve and process more than one queued Kafka message per processing cycle, up to a configured maximum batch size, instead of strictly one message at a time.
- **FR-002**: The maximum batch size for each service's consumption MUST be independently configurable per service (and, for svc-audit, per consumed topic) without requiring a code change.
- **FR-003**: When a batch contains a message that fails to process (e.g., malformed payload or downstream processing error), the system MUST continue processing the remaining valid messages in that batch rather than discarding or blocking the whole batch.
- **FR-004**: Messages that fail processing within a batch MUST be republished immediately (no in-process retry) to a per-service dead-letter topic, preserving the original message content and including failure details, so they can be investigated or reprocessed later.
- **FR-005**: The system MUST NOT lose a message that was successfully processed but not yet acknowledged if the service crashes or restarts mid-batch; such a message must be reprocessed on recovery.
- **FR-006**: The system MUST NOT permanently skip a message that was queued in a batch but not yet processed if the service crashes or restarts mid-batch.
- **FR-011**: The system MUST provide at-least-once delivery semantics; each service's per-message processing logic MUST be idempotent so that reprocessing a duplicate message (e.g., after a mid-batch crash and redelivery) does not corrupt state or produce duplicate downstream effects.
- **FR-007**: Batch processing MUST preserve the existing per-message behavior/business logic of each service (validation, risk assessment, audit recording) — batching changes how many messages are handled per cycle, not what happens to each individual message.
- **FR-008**: The system MUST expose, as structured metrics (e.g., Micrometer counters/gauges) via each service's existing metrics backend, at minimum: the size of each processed batch and the count of successful vs. failed messages within it.
- **FR-009**: svc-audit MUST support batch consumption independently on each of its three consumed topics (`declarations.created`, `validations.completed`, `risk.assessed`), so that batching on one topic's consumer does not require or depend on batching being enabled on the others.
- **FR-010**: When the configured batch size exceeds the number of currently available messages, the system MUST process the available messages without waiting for the batch to fill to capacity.

### Key Entities

- **Message Batch**: A group of Kafka records retrieved together for processing by a single service in one processing cycle; bounded by a configured maximum size and by what is currently available on the topic/partition.
- **Batch Processing Outcome**: The per-batch result of a processing cycle, comprising the batch size, the count of messages processed successfully, and the count/identity of messages that failed, used for observability and failure follow-up.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Under a sustained burst of at least 500 messages on a single topic, the consuming service's end-to-end processing throughput (messages/second) improves measurably compared to today's one-at-a-time consumption, with consumer lag returning to zero materially faster.
- **SC-002**: When a batch contains one malformed message among 50 valid ones, 100% of the valid messages are successfully processed and the malformed message appears on the dead-letter topic — none of the valid messages are lost or duplicated.
- **SC-003**: After a simulated mid-batch service restart, 100% of messages that were queued but not yet processed at the time of restart are eventually processed at least once (no loss, no permanent skip), with any duplicate reprocessing handled safely by each service's idempotent processing logic.
- **SC-004**: An operator can change a service's batch size configuration and observe the new batch size take effect within one service restart, without needing a code change or redeployment of application logic.
- **SC-005**: Batch size and success/failure counts for each processed batch are visible as metrics via the service's existing metrics backend, within the same operational workflow operators already use today.

## Assumptions

- "Batch" refers to the number of Kafka records a service's consumer fetches and hands to application code together in one processing cycle (a Kafka poll/receive batch), not a scheduled/periodic batch job.
- The existing Kafka topics, partitioning, and message schemas (JSON payloads) for `declarations.created`, `validations.completed`, and `risk.assessed` remain unchanged by this feature.
- svc-declare remains a producer only and is out of scope for this feature, since it does not consume from Kafka.
- The existing `ConsumerMapRegistry` per-channel enable/disable mechanism continues to function unchanged alongside batch consumption; toggling a channel off still stops processing regardless of batch size.
- The default maximum batch size at launch is 100 messages per service (per topic for svc-audit), tunable later via configuration based on observed production load.
- Existing message ordering guarantees (per-partition ordering) are preserved; batching processes messages from a partition in the order they were fetched.
- Downstream systems and consumers of each service's own outputs are unaffected by this change — batching is an internal consumption-pattern change, not a change to what each service emits.
- No new infrastructure (e.g., new topics, new consumer groups) is required; this is a configuration and consumer-handling change within the existing three services.
