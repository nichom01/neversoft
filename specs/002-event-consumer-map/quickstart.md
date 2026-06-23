# Quickstart Validation Guide: Event Consumer Map

**Feature**: 002-event-consumer-map  
**Date**: 2026-06-23

See [contracts/consumer-map-schema.md](contracts/consumer-map-schema.md) for the full YAML schema and API contract.  
See [data-model.md](data-model.md) for entity definitions and state transitions.

---

## Prerequisites

- Java 21 installed
- Maven 3.x installed
- Docker + Docker Compose available (for scenario 3)
- Monorepo checked out; `lib-consumer-map` built (`mvn install` in `lib-consumer-map/`)

---

## Scenario 1: Startup Load and Validation

**Goal**: Confirm the library loads the YAML at startup and rejects an invalid file.

### Step 1 — Valid load

Place the baseline `consumer-map.yml` at the monorepo root (see worked example in [contracts/consumer-map-schema.md](contracts/consumer-map-schema.md)).

Run unit tests for `lib-consumer-map`:

```bash
cd lib-consumer-map
mvn test -Dtest=ConsumerMapLoaderTest
```

**Expected**: All tests pass. The loader parses the file and returns a `ConsumerMapSnapshot` with 5 consumer registrations across 3 topics.

### Step 2 — Invalid load

Replace `consumer-map.yml` with a file that has an empty `consumers` list for one topic. Run the test:

```bash
mvn test -Dtest=ConsumerMapLoaderValidationTest
```

**Expected**: The loader throws a `ConsumerMapValidationException` naming the offending topic. The registry retains the previous valid snapshot (tested via integration test that pre-loads a valid state before presenting the invalid file).

---

## Scenario 2: Hot-Reload in Dev Environment

**Goal**: Confirm that disabling a consumer in the YAML takes effect within the poll interval without restarting the service.

### Setup

Start `svc-audit` in dev mode:

```bash
cd svc-audit
mvn quarkus:dev -Dconsumer-map.file=../consumer-map.yml
```

Confirm the service starts and the watcher log line appears:

```
ConsumerMapWatcher started (profile=dev, poll-interval=30s)
```

### Step 1 — Confirm consumer is active

Send a test event to `declarations.created` via the Kafka CLI:

```bash
kafka-console-producer --bootstrap-server localhost:9092 --topic declarations.created
> {"id":"test-001","aggregateType":"declaration","payload":"{}"}
```

**Expected**: `svc-audit` logs a line indicating the `audit-declarations` channel received and processed the message.

### Step 2 — Disable consumer in YAML

Edit `consumer-map.yml` — set `enabled: false` on the `svc-audit` / `audit-declarations` entry. Save the file.

Wait for the poll interval (up to 30 seconds). Watch the service log for:

```
ConsumerMapWatcher: reload detected — 1 consumer(s) changed
```

### Step 3 — Confirm consumer is inactive

Send another message to `declarations.created`.

**Expected**: No processing log from `svc-audit` for `audit-declarations`. The message is delivered by Kafka (consumer group offset advances) but the handler returns immediately without processing.

### Step 4 — Re-enable

Set `enabled: true` and save. After the next poll, send another message.

**Expected**: Processing resumes; `svc-audit` logs receipt and processing.

---

## Scenario 3: Docker Compose Environment (Production Profile)

**Goal**: Confirm hot-reload watcher does NOT start when profile is `prod`.

Start the full stack:

```bash
cd infra
docker-compose up -d
```

Inspect the `svc-audit` container log:

```bash
docker-compose logs svc-audit | grep ConsumerMapWatcher
```

**Expected**: The log contains:

```
ConsumerMapWatcher disabled (profile=prod not in enabled-environments)
```

Edit `consumer-map.yml` on the host (bind-mounted into the container). Wait 60 seconds.

**Expected**: No reload log. Consumer behaviour unchanged. A service restart is required for changes to take effect.

---

## Scenario 4: File Becomes Inaccessible

**Goal**: Confirm that if the YAML file is temporarily unavailable, the last valid snapshot is retained.

With `svc-audit` running in dev mode with the watcher active:

```bash
mv consumer-map.yml consumer-map.yml.bak
```

Wait for the poll interval. Check the service log.

**Expected**:
- Warning log: `ConsumerMapWatcher: file not accessible at <path> — retaining last valid snapshot`
- Consumer behaviour unchanged (still using previous snapshot)

Restore the file:

```bash
mv consumer-map.yml.bak consumer-map.yml
```

**Expected**: On the next poll, the file is reloaded successfully; log confirms reload.

---

## Pass Criteria Summary

| Scenario | Pass When |
|----------|-----------|
| 1 — Valid load | Snapshot contains 5 consumer registrations; loader unit tests pass |
| 1 — Invalid load | Exception thrown; previous snapshot retained |
| 2 — Hot-reload disable | Within 30s of YAML edit, messages no longer processed by that consumer |
| 2 — Hot-reload re-enable | Messages resume processing after next poll |
| 3 — Prod profile | Watcher never starts; YAML edits have no runtime effect |
| 4 — File inaccessible | Warning logged; consumers continue with last valid snapshot |
