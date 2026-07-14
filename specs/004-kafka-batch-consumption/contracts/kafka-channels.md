# Contracts: Kafka Channel Configuration Changes

These are the interface contracts this feature exposes: (1) the consumer-side Kafka configuration contract each service must honor, and (2) the new dead-letter topic message contract. Existing topic schemas (`declarations.created`, `validations.completed`, `risk.assessed` payload JSON) are unchanged.

## 1. Batch consumption configuration contract (per channel)

Applies to `svc-validate` (`declarations-created`), `svc-risk` (`validations-completed`), and `svc-audit` (`audit-declarations`, `audit-validations`, `audit-risk`).

Required `application.properties` keys per consuming channel:

```properties
mp.messaging.incoming.<channel>.batch=true
mp.messaging.incoming.<channel>.max.poll.records=100
```

- `batch` MUST be `true` for all six consumer channels listed above.
- `max.poll.records` MUST default to `100` and MUST be overridable per channel per environment (e.g. via `%prod.` profile or env var `MP_MESSAGING_INCOMING_<CHANNEL>_MAX_POLL_RECORDS`) without a code change (FR-002).
- All other existing keys (`connector`, `topic`, `group.id`, `value.deserializer`, `auto.offset.reset`) are unchanged.
- The `%test` profile override redirecting each channel to `smallrye-in-memory` is unchanged; the in-memory connector also supports batch delivery via `List<Message<T>>` payloads in tests.

## 2. `@Incoming` method signature contract

Each consumer method changes from:

```java
@Incoming("<channel>")
public void consume(String messageJson) throws Exception
```

to:

```java
@Incoming("<channel>")
public void consume(List<Message<String>> batch)
```

Contract obligations of the method body:

- MUST check `registry.isEnabled("<channel>")` once per batch (unchanged behavior: if disabled, the whole batch is skipped/not acked — this is existing pre-batch behavior applied at the batch level, since the registry check already occurred once per record before and disabling now uniformly skips the batch).
- MUST iterate every `Message<String>` in the batch individually.
- For each record: MUST deserialize and invoke the existing per-message service call unchanged (FR-007); on success, MUST call `.ack()` on that record's `Message`; on failure, MUST publish to the dead-letter topic (contract 3) and then `.ack()` (dead-lettering counts as terminal handling, not a redelivery-triggering failure) — see research.md Decision 2/3.
- MUST NOT let one record's exception propagate out of the batch method and fail the whole batch.

## 3. Dead-letter topic contract

One new topic per existing consumed channel:

| Source topic | Dead-letter topic | Partitions | Retention |
|---|---|---|---|
| `declarations.created` | `declarations.created.dlq` | 1 | 604800000 ms (7 days, matches source) |
| `validations.completed` | `validations.completed.dlq` | 1 | 604800000 ms |
| `risk.assessed` | `risk.assessed.dlq` | 1 | 604800000 ms |

Message value (JSON) published to each `.dlq` topic:

```json
{
  "originalPayload": "<verbatim original message JSON as a string>",
  "originalTopic": "declarations.created",
  "failureReason": "short exception message / description",
  "failedAt": "2026-07-14T10:15:30Z",
  "serviceName": "svc-validate"
}
```

- `originalPayload` MUST be the untouched original message string (not re-parsed/re-serialized), so it can be replayed onto the source topic later if desired.
- Each service publishing to a `.dlq` topic MUST use its own Kafka producer `@Channel`, configured via the same `kafka.bootstrap.servers` already set for that service.

## 4. Metrics contract

Each of the three services MUST expose, via the added `quarkus-micrometer-registry-prometheus` extension's default `/q/metrics` endpoint:

| Metric name | Type | Tags | Meaning |
|---|---|---|---|
| `kafka.batch.size` | DistributionSummary | `channel` | Size of each processed batch |
| `kafka.batch.records.success` | Counter | `channel` | Successfully processed records |
| `kafka.batch.records.deadlettered` | Counter | `channel` | Records republished to the dead-letter topic |

No breaking changes to any existing `/q/metrics` output (extension is additive; no existing service currently uses Micrometer).
