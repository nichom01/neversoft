# ADR-003: Event Consumer Map — Single YAML Source of Truth with Hot-Reload

**Status:** Accepted  
**Date:** 2026-06-23  
**Deciders:** Engineering PoC Team

---

## Context

Consumer topic bindings were scattered across four service `application.properties` files
(`mp.messaging.incoming.<channel>.topic`, `.group.id`, etc.). There was no single place to see
which services consumed which topics, and changing routing required editing multiple files and
redeploying services.

The need arose for:
1. A single authoritative record of all topic-to-consumer relationships
2. The ability to pause a specific consumer without a service restart in dev/staging

---

## Decision

### Decision 1: Polling file-watcher at the application routing layer (not dynamic Kafka re-subscription)

**Chosen:** Each `@Incoming` handler calls `ConsumerMapRegistry.isEnabled(channelName)` at the top
of its method body. A daemon thread polls `consumer-map.yml` every `pollIntervalSeconds` seconds and
atomically replaces the in-memory snapshot on a valid change.

**Rejected:** Dynamic Kafka re-subscription (unsubscribing/re-subscribing channels at runtime).
Quarkus SmallRye Reactive Messaging registers `@Incoming` CDI beans at build time; Kafka
subscriptions are established during container startup and cannot be torn down and re-created without
a service restart. There is no supported Quarkus API for runtime channel management.

### Decision 2: Shared library (`lib-consumer-map`) not a central routing service

**Chosen:** A plain Maven JAR installed to the local `.m2` cache and added as a compile dependency.
The `ConsumerMapRegistry` CDI bean is discovered by each consuming Quarkus app at build time.

**Rejected:** A dedicated routing microservice that consumer services poll or subscribe to. This
would introduce a network dependency on the hot path of every message delivery, adding latency and
a new failure mode. The shared library approach has zero network overhead — `isEnabled()` is a
`volatile` read of an immutable in-memory record.

### Decision 3: YAML file at monorepo root, configurable via `consumer-map.file` property

**Chosen:** `consumer-map.yml` at the repository root, with a default `consumer-map.file=../consumer-map.yml`
for local/dev runs and a `%prod.consumer-map.file=/config/consumer-map.yml` for the Docker Compose
bind-mount path.

**Rationale:** Keeps the file discoverable (visible in the root alongside `README.md`, `CLAUDE.md`).
The property override allows each deployment environment to supply the file differently without
code changes.

### Decision 4: Environment gating via YAML `hot-reload.enabled-environments` list

**Chosen:** The `ConsumerMapWatcher` reads `quarkus.profile` and compares it against
`hot-reload.enabled-environments` from the loaded snapshot. If the profile is not listed, the
watcher thread never starts and the startup snapshot is permanent for the process lifetime.

**Rationale:** Keeps the gating declaration co-located with the consumer map itself, not in
environment-specific properties files. The YAML is the single source of truth for both routing and
reload policy. Prod services get a stable, immutable snapshot with no polling overhead.

### Decision 5: `isEnabled()` performance — volatile read, no I/O or locking

The `ConsumerMapRegistry` holds the active snapshot behind a `volatile` reference. Swapping the
snapshot is a single atomic reference write (no locks). Reading the snapshot is a single volatile
read followed by a linear scan of in-memory records. For the current scale (5 consumers across
3 topics), this completes in well under 1 µs.

---

## Consequences

**Positive:**
- Single `consumer-map.yml` at the repo root is the authoritative record of all topic→consumer bindings
- Hot-reload in dev/staging without service restart (within the configured poll interval)
- Zero runtime overhead in production (watcher thread never starts, `isEnabled()` is a memory read)
- Validation at load time rejects malformed files and retains the last valid snapshot

**Negative/Trade-offs:**
- Each `@Incoming` handler must include a one-line `isEnabled()` guard (3 services × up to 3 channels)
- Consumers that are "disabled" still receive messages from Kafka (offset advances); they just discard
  them without processing. Message throughput is not reduced; only CPU for the discarded handling is saved
- Hot-reload reflects file changes within `pollIntervalSeconds` (default 30s), not immediately

---

## References

- [spec.md](../../specs/002-event-consumer-map/spec.md)
- [research.md](../../specs/002-event-consumer-map/research.md)
- [contracts/consumer-map-schema.md](../../specs/002-event-consumer-map/contracts/consumer-map-schema.md)
- ADR-001: Kafka client library choice (SmallRye Reactive Messaging via `quarkus-smallrye-reactive-messaging-kafka`)
