# Data Model: Event Consumer Map

**Feature**: 002-event-consumer-map  
**Date**: 2026-06-23

---

## Entities

### ConsumerMap (root document)

The top-level object parsed from `consumer-map.yml`. Holds the schema version, hot-reload policy, and the full set of event entries.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `version` | String | Yes | Schema version. Current: `"1.0"` |
| `hot-reload` | HotReloadConfig | Yes | Controls which environments permit runtime reloading |
| `events` | Map<String, EventEntry> | Yes | Keyed by Kafka topic name; at least one entry required |

**Invariants**:
- `version` must be a recognised value; unknown versions fail validation
- `events` must not be empty

---

### HotReloadConfig

Controls the file-watcher behaviour at runtime.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `enabled-environments` | List<String> | Yes | — | Quarkus profile names that permit hot-reload (e.g. `dev`, `local`, `staging`) |
| `poll-interval-seconds` | Integer | No | `30` | How often the file-watcher checks for changes |

**Invariants**:
- `poll-interval-seconds` must be ≥ 5
- `enabled-environments` may be empty (disables hot-reload everywhere)

---

### EventEntry

Describes one Kafka topic and all declared consumers for it.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `consumers` | List<ConsumerRegistration> | Yes | One or more consumer declarations for this topic |

**Invariants**:
- `consumers` must contain at least one entry
- No two entries within the same EventEntry may share the same `channel` value

---

### ConsumerRegistration

One declared mapping of a Kafka topic to a specific service and channel.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `service` | String | Yes | Human-readable name of the consuming service (e.g. `svc-audit`) |
| `channel` | String | Yes | The SmallRye channel name matching `mp.messaging.incoming.<channel>` |
| `enabled` | Boolean | No (default: `true`) | Whether this consumer is active. Set to `false` to pause routing without deleting the entry |

**Invariants**:
- `service` and `channel` are non-empty strings
- `channel` must match the pattern `[a-z][a-z0-9-]*` (lowercase kebab-case)
- `enabled` defaults to `true` if absent

---

### ConsumerMapSnapshot (runtime)

An immutable, point-in-time copy of the parsed ConsumerMap held in the ConsumerMapRegistry CDI bean. Replaced atomically when a reload occurs.

| Field | Type | Description |
|-------|------|-------------|
| `loadedAt` | Instant | When this snapshot was loaded |
| `filePath` | Path | Absolute path to the source file |
| `map` | ConsumerMap | The parsed document |

**Invariants**:
- Snapshots are never mutated after creation; a new snapshot replaces the old one
- The registry holds exactly one active snapshot at any time

---

## State Transitions

```
[No file] ──────────────────────────────────► FAILED (startup error)
                                                   │ file becomes available
[File present, valid] ──── startup load ──────► LOADED (snapshot v1 active)
                                                   │ poll detects change
                      ──── reload: valid ─────► LOADED (snapshot v2 active, v1 discarded)
                      ──── reload: invalid ───► LOADED (v1 retained; error logged)
                      ──── file deleted ──────► LOADED (v1 retained; warning logged)
```

---

## YAML Schema Example (current baseline)

```yaml
version: "1.0"

hot-reload:
  enabled-environments:
    - local
    - dev
    - staging
  poll-interval-seconds: 30

events:
  declarations.created:
    consumers:
      - service: svc-validate
        channel: declarations-created
        enabled: true
      - service: svc-audit
        channel: audit-declarations
        enabled: true

  validations.completed:
    consumers:
      - service: svc-risk
        channel: validations-completed
        enabled: true
      - service: svc-audit
        channel: audit-validations
        enabled: true

  risk.assessed:
    consumers:
      - service: svc-audit
        channel: audit-risk
        enabled: true
```

---

## Library Java Types (logical, not prescriptive)

```
uk.co.neversoft.consumermap
├── ConsumerMap                  // top-level domain record
├── HotReloadConfig              // embedded config record
├── EventEntry                   // per-topic record
├── ConsumerRegistration         // per-consumer record
├── ConsumerMapSnapshot          // immutable runtime snapshot
├── ConsumerMapLoader            // parse + validate YAML → ConsumerMap
├── ConsumerMapWatcher           // poll loop; fires CDI event on change
├── ConsumerMapRegistry          // @ApplicationScoped CDI bean; holds active snapshot
└── ConsumerMapChangedEvent      // CDI event payload carrying old + new snapshot
```
