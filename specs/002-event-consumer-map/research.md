# Research: Event Consumer Map

**Feature**: 002-event-consumer-map  
**Date**: 2026-06-23

---

## Decision 1: Hot-Reload Mechanism

**Decision**: Use a polling file-watcher (not filesystem events) to detect YAML changes at runtime. Services check the in-memory registry on each incoming message.

**Rationale**: SmallRye Reactive Messaging `@Incoming` annotated methods are CDI beans registered at application startup. Kafka subscriptions (consumer group registration, partition assignment) cannot be dynamically added or removed without restarting the JVM. The only viable hot-reload target is the **routing layer** — after a message is delivered by Kafka to the `@Incoming` method, the consumer bean checks the current ConsumerMapRegistry to decide whether to process or discard it. The file-watcher detects changes to the YAML and updates the in-memory registry; no JVM restart needed.

**Why polling, not filesystem watch**: `java.nio.file.WatchService` has inconsistent behavior across platforms (macOS uses polling internally anyway). A configurable polling interval is simpler, portable, and avoids platform-specific edge cases. Default: 30 seconds.

**Alternatives considered**:
- *Dynamic Kafka consumer re-subscription* (programmatic `KafkaConsumer.subscribe()`): rejected because it requires bypassing Quarkus CDI-managed channels entirely and loses backpressure, health check integration, and offset management that SmallRye provides.
- *Quarkus dev mode live-reload* (full JVM restart on file change): rejected because it's too blunt — restarts all services, not just updates routing, and doesn't work in staging/docker environments.
- *MicroProfile Config ConfigSource watching mp.messaging.** properties*: rejected because `mp.messaging.*` properties are consumed during channel construction at startup; changing them at runtime has no effect on existing channel bindings.
- *Vertx EventBus trigger → consumer pause/resume*: possible but adds Vertx dependency coupling; the routing-table approach is simpler and achieves the same observable result.

---

## Decision 2: Scope of "Consumer" in the YAML

**Decision**: A consumer entry in the YAML maps one Kafka topic to one named service + channel pair. The channel name corresponds to the `mp.messaging.incoming.<channel>` key already in each service's `application.properties`.

**Rationale**: The existing consumer configuration uses SmallRye channel names as the binding key. Reusing those names in the YAML avoids introducing a new naming layer and makes the YAML directly traceable to existing configuration. The YAML adds an `enabled` flag and the `service` name for human readability; it does not replace the channel's Kafka configuration.

**Alternatives considered**:
- *Replace application.properties channel config entirely with YAML*: rejected for v1 because it requires each service to load the YAML as a ConfigSource before SmallRye initialises channels. Feasible in future but out of scope here.
- *Map by consumer group ID only (no service name)*: rejected because consumer group IDs are not self-documenting; the service name adds the human-readable context that makes the YAML valuable as an operations artefact.

---

## Decision 3: Shared Library vs Central Routing Service

**Decision**: Implement a shared Java library (`lib-consumer-map`) that each service includes as a Maven dependency. No new service.

**Rationale**: A central routing service (a 5th microservice) would introduce a network call on the hot path for every Kafka message — adding latency, a new failure mode, and a new deployment dependency. The consumer map is a configuration concern, not a runtime processing concern; it belongs in the library layer. Each service loads and watches the file independently. This matches the existing project pattern (four independent services, each with their own Kafka bindings).

**Alternatives considered**:
- *Central routing microservice*: rejected — adds network latency on every message, single point of failure, over-engineered for a configuration concern.
- *Inline the watcher into each service with copy-pasted code*: rejected — violates DRY and means schema or polling changes require 4 separate PRs.

---

## Decision 4: YAML File Location

**Decision**: The file is named `consumer-map.yml` and lives at the monorepo root. Each service resolves the path via a configurable `application.properties` key (`consumer-map.file`), defaulting to the project root location.

**Rationale**: Placing the file at the monorepo root makes it immediately visible to anyone opening the repository — it is the first artefact an engineer finds when asking "what consumes what?" A configurable path property (using MicroProfile Config / `@ConfigProperty`) allows individual services to override the location when running in Docker Compose (where the file is bind-mounted) or in CI.

**Alternatives considered**:
- *In `infra/` directory*: rejected — the file is not an infra concern, it is a cross-cutting operational concern for all services.
- *Embedded per-service resource*: rejected — defeats the purpose of a single file.

---

## Decision 5: Environment-Gating of Hot-Reload

**Decision**: The YAML itself declares which Quarkus profiles are permitted to hot-reload (e.g., `dev`, `local`, `staging`). The library reads the active Quarkus profile (`quarkus.profile`) at startup and only starts the file-watcher if the profile is listed. Production profiles are excluded by default.

**Rationale**: Tying the hot-reload gate to the YAML file means the policy is visible alongside the mapping configuration. An operator editing the YAML can see at a glance which environments allow hot-reload. Quarkus profile (`%prod`, `%dev`, etc.) is already the established convention in this project for environment-specific behaviour.

**Alternatives considered**:
- *Separate environment variable gate*: rejected — splits the configuration across two files, harder to audit.
- *Always-on hot-reload*: rejected — unsafe for production; accidental file edits could silently reroute live traffic.

---

## Current Consumer Map (baseline, derived from application.properties)

| Topic | Service | Channel | Consumer Group |
|-------|---------|---------|----------------|
| `declarations.created` | svc-validate | `declarations-created` | `svc-validate` |
| `declarations.created` | svc-audit | `audit-declarations` | `svc-audit-declarations` |
| `validations.completed` | svc-risk | `validations-completed` | `svc-risk` |
| `validations.completed` | svc-audit | `audit-validations` | `svc-audit-validations` |
| `risk.assessed` | svc-audit | `audit-risk` | `svc-audit-risk` |
