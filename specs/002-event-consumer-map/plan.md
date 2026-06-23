# Implementation Plan: Event Consumer Map

**Branch**: `002-event-consumer-map` | **Date**: 2026-06-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-event-consumer-map/spec.md`

## Summary

Build a shared Java library (`lib-consumer-map`) that reads a single `consumer-map.yml` file at the monorepo root, exposes an `@ApplicationScoped` CDI registry bean that consumer services query per-message, and runs a polling file-watcher in dev/local/staging environments to detect YAML changes and update routing at runtime without any service restart. Production profiles do not start the watcher; the startup snapshot is permanent.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Quarkus 3.15.3, SmallRye Reactive Messaging (Kafka), Jackson Dataformat YAML (`com.fasterxml.jackson.dataformat:jackson-dataformat-yaml`), MicroProfile Config (`@ConfigProperty`)

**Storage**: YAML file on disk; in-memory volatile reference for active snapshot. No database.

**Testing**: Maven Surefire 3.3.1 (unit), Maven Failsafe 3.3.1 (integration against existing `integration-tests/` module)

**Target Platform**: JVM (GraalVM native image compatibility is a stretch goal, not required for v1)

**Project Type**: Shared library (`lib-consumer-map`) consumed by existing Quarkus microservices

**Performance Goals**: `isEnabled()` check must complete in < 1 µs (volatile read of immutable record — no I/O or locking on the hot path)

**Constraints**: No root aggregator POM; each module is built independently with `mvn` in its own directory. `lib-consumer-map` is installed to local `.m2` cache and referenced by the four consumer services as a `<scope>compile</scope>` dependency.

**Scale/Scope**: 1 new library module + changes to 4 existing service `pom.xml` files + 4 consumer bean classes + 1 YAML file at repo root

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

The constitution file contains only template placeholders — no project-specific principles have been established. No gates apply. Proceed.

## Project Structure

### Documentation (this feature)

```text
specs/002-event-consumer-map/
├── plan.md              ← this file
├── spec.md              ← feature specification
├── research.md          ← Phase 0: decisions and rationale
├── data-model.md        ← Phase 1: logical entities and schemas
├── contracts/
│   └── consumer-map-schema.md   ← Phase 1: YAML schema + Java API contract
├── quickstart.md        ← Phase 1: validation scenarios
└── tasks.md             ← Phase 2 output (created by /speckit-tasks)
```

### Source Code (repository root)

```text
consumer-map.yml                              # Single source of truth; monorepo root

lib-consumer-map/
├── pom.xml
└── src/
    ├── main/java/uk/co/neversoft/consumermap/
    │   ├── ConsumerMap.java                  # Top-level domain record
    │   ├── HotReloadConfig.java              # Embedded config record
    │   ├── EventEntry.java                   # Per-topic record
    │   ├── ConsumerRegistration.java         # Per-consumer record
    │   ├── ConsumerMapSnapshot.java          # Immutable runtime snapshot
    │   ├── ConsumerMapLoader.java            # Parse + validate YAML → ConsumerMap
    │   ├── ConsumerMapRegistry.java          # @ApplicationScoped CDI bean
    │   ├── ConsumerMapWatcher.java           # Polling file-watcher (dev/staging only)
    │   └── ConsumerMapChangedEvent.java      # CDI event payload
    └── test/java/uk/co/neversoft/consumermap/
        ├── ConsumerMapLoaderTest.java
        ├── ConsumerMapLoaderValidationTest.java
        ├── ConsumerMapRegistryTest.java
        └── ConsumerMapWatcherTest.java

# Existing services — each adds lib-consumer-map dependency + isEnabled() guard:
svc-validate/src/main/java/.../DeclarationConsumer.java   # inject registry
svc-risk/src/main/java/.../ValidationConsumer.java        # inject registry
svc-audit/src/main/java/.../AuditConsumer.java            # inject registry (3 channels)
```

**Structure Decision**: Single new library module following the existing per-directory Maven pattern. No root aggregator POM; `lib-consumer-map` is built first and installed to `.m2`. Consumer services reference it as a compile dependency and inject `ConsumerMapRegistry` into their `@Incoming` handler beans.

## Key Design Decisions

See [research.md](research.md) for full rationale. Summary:

| Decision | Choice |
|----------|--------|
| Hot-reload mechanism | Polling file-watcher (30s default); watcher updates in-memory registry; `@Incoming` handlers call `isEnabled()` per message |
| Hot-reload gating | YAML `hot-reload.enabled-environments` list checked against `quarkus.profile` at startup |
| Library vs central service | Shared library — avoids network dependency on the hot path |
| File location | Monorepo root; path configurable via `consumer-map.file` property |
| `isEnabled()` performance | Volatile read of immutable record — no I/O, no locks |

## Implementation Phases

### Phase 1: Library Core

1. Create `lib-consumer-map/pom.xml` (Quarkus BOM, Jackson YAML, Arc CDI)
2. Implement domain records: `ConsumerMap`, `HotReloadConfig`, `EventEntry`, `ConsumerRegistration`, `ConsumerMapSnapshot`
3. Implement `ConsumerMapLoader`: Jackson YAML deserialization + validation rules from [data-model.md](data-model.md)
4. Implement `ConsumerMapRegistry`: `@ApplicationScoped` CDI bean; volatile `ConsumerMapSnapshot` reference; `isEnabled(String channelName)` method
5. Implement `ConsumerMapWatcher`: Quarkus `@Startup` bean; reads `quarkus.profile` and `hot-reload.enabled-environments`; starts polling thread if permitted; fires `ConsumerMapChangedEvent` on valid change

### Phase 2: Integration into Consumer Services

For each of `svc-validate`, `svc-risk`, `svc-audit`:

1. Add `lib-consumer-map` Maven dependency
2. Add `consumer-map.file` property to `application.properties` (default `../consumer-map.yml`; `%prod` override for Docker Compose mount path)
3. Inject `ConsumerMapRegistry` into the `@Incoming` handler bean
4. Add `isEnabled()` guard at the top of each `@Incoming` method

### Phase 3: YAML File + Tests

1. Create `consumer-map.yml` at monorepo root with the baseline mapping (see [data-model.md](data-model.md#yaml-schema-example))
2. Update `lib-consumer-map` unit tests to cover all validation rules in [contracts/consumer-map-schema.md](contracts/consumer-map-schema.md)
3. Add integration test in `integration-tests/` verifying hot-reload: disable a consumer in YAML, assert messages are discarded, re-enable, assert processing resumes

## Complexity Tracking

No constitution violations. No complexity justification required.
