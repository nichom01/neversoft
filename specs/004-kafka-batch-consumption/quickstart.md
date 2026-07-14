# Quickstart: Validating Kafka Batch Consumption

## Prerequisites

- Docker (for `infra/docker-compose.yml`: Kafka, Zookeeper, per-service PostgreSQL instances)
- `JAVA_HOME` set to the Java 21 installation (see project root `CLAUDE.md` — Quarkus 3.15.3's Byte Buddy does not support Java 25):
  ```bash
  export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.8/libexec/openjdk.jdk/Contents/Home
  ```
- Implementation complete per `plan.md` / `tasks.md`: `batch=true` + `max.poll.records` configured on all six consumer channels, `List<Message<String>>` consumer signatures, dead-letter publishing wired up, Micrometer metrics added, and the three `.dlq` topics added to `infra/docker-compose.yml`'s `kafka-setup` step.

## 1. Start the stack

```bash
docker compose -f infra/docker-compose.yml up -d
```

Wait for `kafka-setup` to report `All topics created.` (now includes the three `.dlq` topics per contracts/kafka-channels.md).

## 2. Validate User Story 1 — batch throughput

1. Publish a burst of ≥500 messages to `declarations.created` (a well-formed `DeclarationCreatedEvent` JSON payload, varying an identifying field per message).
2. Tail `svc-validate` logs and confirm log lines indicate multiple records handled per invocation (batch size > 1) rather than one-by-one.
3. Query `svc-validate`'s `/q/metrics` endpoint and confirm `kafka.batch.size{channel="declarations-created"}` shows samples with count > 1.
4. Confirm consumer lag for `svc-validate`'s consumer group (`kafka-consumer-groups --describe --group svc-validate`) returns to 0 within a reasonable window, without growing unbounded during the burst.

**Expected outcome**: batches larger than 1 are observed, and lag drains — matches SC-001.

## 3. Validate User Story 2 — partial batch failure isolation

1. Publish a mixed batch to `declarations.created`: 50 valid `DeclarationCreatedEvent` payloads and 1 deliberately malformed payload (invalid JSON).
2. Confirm (via `svc-validate`'s database/API, per existing service behavior) all 50 valid declarations were processed.
3. Consume from `declarations.created.dlq` and confirm exactly one message appears, with `originalPayload` matching the malformed input and `failureReason` populated.
4. Confirm `kafka.batch.records.success` incremented by 50 and `kafka.batch.records.deadlettered` incremented by 1 for that batch.

**Expected outcome**: matches SC-002 — no valid message lost or duplicated, malformed message identifiable on the DLQ topic.

## 4. Validate mid-batch crash recovery (FR-005/FR-006/FR-011)

1. Publish a batch of messages to `validations.completed`.
2. Kill `svc-risk` (`docker compose kill svc-risk`) partway through processing (e.g., attach a debugger or add a temporary delay to simulate a slow batch, then kill mid-batch).
3. Restart `svc-risk` (`docker compose up -d svc-risk`).
4. Confirm every message from the original batch is eventually reflected in `svc-risk`'s output (processed or dead-lettered) — none are permanently missing.
5. Note: some messages may be processed twice (at-least-once); confirm reprocessing a duplicate does not corrupt state (idempotent by design, per FR-011) and does not appear as an error.

**Expected outcome**: matches SC-003 — no loss, no permanent skip, safe duplicate handling.

## 5. Validate User Story 3 — configurability

1. Change `mp.messaging.incoming.declarations-created.max.poll.records` for `svc-validate` (e.g., via an env var override in `infra/docker-compose.yml`) from `100` to `10`.
2. Restart `svc-validate` only.
3. Republish a burst of messages and confirm `kafka.batch.size` samples no longer exceed 10.

**Expected outcome**: matches SC-004 — new batch size takes effect after one restart, no code change.

## 6. Regression check

Run the existing test suites to confirm no per-message business logic regressed:

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.8/libexec/openjdk.jdk/Contents/Home mvn -f svc-validate/pom.xml test
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.8/libexec/openjdk.jdk/Contents/Home mvn -f svc-risk/pom.xml test
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.8/libexec/openjdk.jdk/Contents/Home mvn -f svc-audit/pom.xml test
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.8/libexec/openjdk.jdk/Contents/Home mvn -f integration-tests/pom.xml test
```
