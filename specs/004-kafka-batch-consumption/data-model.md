# Data Model: Kafka Batch Record Consumption

This feature changes how existing messages are consumed (individually vs. in batches) and adds one new artifact — the dead-letter envelope — published when a record fails. It does not add persistent entities or change existing database schemas; no Flyway migrations are required.

## Message Batch (conceptual, in-memory only)

Not a persisted entity — represents the set of records SmallRye Reactive Messaging hands to a single `@Incoming` invocation.

| Field | Type | Description |
|---|---|---|
| records | `List<Message<String>>` | Up to `max.poll.records` unwrapped Kafka messages fetched in one poll cycle, each individually ack/nack-able |
| channel | String | The MicroProfile Reactive Messaging channel name (e.g. `declarations-created`) the batch was delivered on |

**Rules**:
- Batch size is bounded by the channel's configured `max.poll.records` (default 100) and by how many records are currently available; a batch may be smaller than the configured maximum (FR-010).
- Records within a batch retain their original per-partition order (topics are single-partition; FIFO order preserved end-to-end).

## Batch Processing Outcome (metrics, not persisted)

Represents the result of handling one Message Batch, expressed as Micrometer metrics rather than stored records.

| Field | Type | Description |
|---|---|---|
| channel | String (metric tag) | Which channel/topic the batch came from |
| batchSize | int (DistributionSummary sample) | Number of records in the batch |
| successCount | long (Counter increment) | Records processed and acked successfully |
| deadLetteredCount | long (Counter increment) | Records that failed processing and were republished to the dead-letter topic |

**Rules**:
- `successCount + deadLetteredCount == batchSize` for every batch, once all records in the batch have been handled (FR-008).
- No individual outcome record is persisted to a database; this is observability data only (Decision 5 in research.md).

## Dead-Letter Message Envelope (new Kafka message shape, on `<topic>.dlq`)

Published by the failing service when an individual record's processing fails (FR-004). Not a database entity — a Kafka message on a new per-channel dead-letter topic.

| Field | Type | Description |
|---|---|---|
| originalPayload | String | The unmodified original message JSON that failed to process |
| originalTopic | String | Source topic the failed record came from |
| failureReason | String | Exception message / short description of why processing failed |
| failedAt | ISO-8601 timestamp | When the failure was recorded |
| serviceName | String | Which service (`svc-validate`, `svc-risk`, `svc-audit`) dead-lettered the message |

**Rules**:
- `originalPayload` MUST be preserved verbatim (not re-serialized/mutated) so the record can be manually reprocessed later.
- One dead-letter message per failed record (no batching of failures into one DLQ entry), matching FR-004's "per-record" isolation.

## Relationships

- Each existing domain entity (Declaration, Validation Result, Risk Assessment, Audit Record — unchanged by this feature) continues to be created/updated by the same per-message business logic (`ValidationService.validate`, etc.), now invoked once per record inside a batch loop instead of once per `@Incoming` call. FR-007 requires this per-message logic to be otherwise unchanged.
- No new relationships between existing entities are introduced. The Dead-Letter Message Envelope references an existing message's topic/payload but is not linked to any existing entity by foreign key — it lives entirely in Kafka, not in a service's database.
