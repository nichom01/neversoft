# Tasks: Event Consumer Map

**Input**: Design documents from `specs/002-event-consumer-map/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/consumer-map-schema.md](contracts/consumer-map-schema.md)

**Organization**: Tasks grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no shared dependencies)
- **[Story]**: User story this task belongs to (US1, US2, US3)
- Exact file paths included in every description

---

## Phase 1: Setup

**Purpose**: Create the new `lib-consumer-map` library module structure.

- [x] T001 Create `lib-consumer-map/pom.xml` with Quarkus 3.15.3 BOM, `jackson-dataformat-yaml`, `quarkus-arc`, and `quarkus-smallrye-health` dependencies (groupId: `uk.co.neversoft`, artifactId: `lib-consumer-map`, version: `1.0.0-SNAPSHOT`)
- [x] T002 Create directory tree `lib-consumer-map/src/main/java/uk/co/neversoft/consumermap/` and `lib-consumer-map/src/test/java/uk/co/neversoft/consumermap/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Domain records, YAML loader, and CDI registry — required by all three user stories.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [x] T003 [P] Create `ConsumerRegistration.java` record in `lib-consumer-map/src/main/java/uk/co/neversoft/consumermap/ConsumerRegistration.java` with fields: `service` (String), `channel` (String), `enabled` (boolean, default true)
- [x] T004 [P] Create `HotReloadConfig.java` record in `lib-consumer-map/src/main/java/uk/co/neversoft/consumermap/HotReloadConfig.java` with fields: `enabledEnvironments` (List<String>), `pollIntervalSeconds` (int, default 30)
- [x] T005 [P] Create `EventEntry.java` record in `lib-consumer-map/src/main/java/uk/co/neversoft/consumermap/EventEntry.java` with field: `consumers` (List<ConsumerRegistration>)
- [x] T006 Create `ConsumerMap.java` record in `lib-consumer-map/src/main/java/uk/co/neversoft/consumermap/ConsumerMap.java` with fields: `version` (String), `hotReload` (HotReloadConfig), `events` (Map<String, EventEntry>) — depends on T003, T004, T005
- [x] T007 Create `ConsumerMapSnapshot.java` record in `lib-consumer-map/src/main/java/uk/co/neversoft/consumermap/ConsumerMapSnapshot.java` with fields: `loadedAt` (Instant), `filePath` (Path), `map` (ConsumerMap) — depends on T006
- [x] T008 Implement `ConsumerMapLoader.java` in `lib-consumer-map/src/main/java/uk/co/neversoft/consumermap/ConsumerMapLoader.java`: Jackson YAML deserialization into `ConsumerMap`, plus all 8 validation rules from `contracts/consumer-map-schema.md`; throw `ConsumerMapValidationException` (create this class in same package) on failure — depends on T006, T007
- [x] T009 Implement `ConsumerMapRegistry.java` `@ApplicationScoped` CDI bean in `lib-consumer-map/src/main/java/uk/co/neversoft/consumermap/ConsumerMapRegistry.java`: inject `@ConfigProperty(name = "consumer-map.file") String filePath`; on `@PostConstruct` call `ConsumerMapLoader` and store snapshot; expose `isEnabled(String channelName)` (volatile snapshot read) and `activeSnapshot()` — depends on T007, T008

**Checkpoint**: Foundation ready — `ConsumerMapRegistry.isEnabled()` can be called by any service bean after this phase.

---

## Phase 3: User Story 1 — Define Event Consumer Mappings in YAML (Priority: P1) 🎯 MVP

**Goal**: A single `consumer-map.yml` at the monorepo root is the authoritative record of all topic-to-consumer relationships. Each consumer service checks the registry on message receipt and processes or discards accordingly.

**Independent Test**: Start any one consumer service in dev mode with a valid `consumer-map.yml`. Send a message to its subscribed Kafka topic and confirm the service processes it. Set `enabled: false` for that consumer entry, restart the service, send again — message must be silently discarded.

- [x] T010 [US1] Create `consumer-map.yml` at monorepo root with the 5-consumer baseline mapping (all 3 topics, all enabled: true) matching the schema in `contracts/consumer-map-schema.md`
- [x] T011 [P] [US1] Add `lib-consumer-map` compile dependency to `svc-validate/pom.xml` and add `consumer-map.file=../consumer-map.yml` (+ `%prod.consumer-map.file=/config/consumer-map.yml`) to `svc-validate/src/main/resources/application.properties`
- [x] T012 [P] [US1] Add `lib-consumer-map` compile dependency to `svc-risk/pom.xml` and add `consumer-map.file=../consumer-map.yml` (+ `%prod.consumer-map.file=/config/consumer-map.yml`) to `svc-risk/src/main/resources/application.properties`
- [x] T013 [P] [US1] Add `lib-consumer-map` compile dependency to `svc-audit/pom.xml` and add `consumer-map.file=../consumer-map.yml` (+ `%prod.consumer-map.file=/config/consumer-map.yml`) to `svc-audit/src/main/resources/application.properties`
- [x] T014 [US1] Inject `ConsumerMapRegistry` into `svc-validate/src/main/java/uk/co/neversoft/validate/messaging/DeclarationConsumer.java` and add `if (!registry.isEnabled("declarations-created")) return;` guard at the top of the `@Incoming` method — depends on T009, T011
- [x] T015 [US1] Inject `ConsumerMapRegistry` into `svc-risk/src/main/java/uk/co/neversoft/risk/messaging/ValidationConsumer.java` and add `if (!registry.isEnabled("validations-completed")) return;` guard — depends on T009, T012
- [x] T016 [US1] Inject `ConsumerMapRegistry` into `svc-audit/src/main/java/uk/co/neversoft/audit/messaging/AuditConsumer.java` and add `isEnabled()` guards for all three channels: `"audit-declarations"`, `"audit-validations"`, `"audit-risk"` — depends on T009, T013
- [x] T017 [P] [US1] Write `ConsumerMapLoaderTest.java` in `lib-consumer-map/src/test/java/uk/co/neversoft/consumermap/ConsumerMapLoaderTest.java` covering: valid baseline YAML loads with 5 registrations; each of the 8 validation-failure cases throws `ConsumerMapValidationException` with a message naming the offending field
- [x] T018 [P] [US1] Write `ConsumerMapRegistryTest.java` in `lib-consumer-map/src/test/java/uk/co/neversoft/consumermap/ConsumerMapRegistryTest.java` covering: `isEnabled()` returns true for a present+enabled channel; false for a present+disabled channel; false for an unknown channel name; `activeSnapshot()` is never null after `@PostConstruct`

**Checkpoint**: User Story 1 is fully functional. `lib-consumer-map` unit tests pass. Each service compiles with the registry guard. `consumer-map.yml` is the single source of truth.

---

## Phase 4: User Story 2 — Hot-Load Consumer Mappings (Priority: P2)

**Goal**: Editing `consumer-map.yml` while a service runs in a hot-reload-enabled environment causes the new mappings to take effect within the configured poll interval, without a restart.

**Independent Test**: Start a consumer service in dev mode. Disable a consumer entry in the YAML. Within 30 seconds, send a message to that topic and confirm the service discards it. Re-enable and confirm processing resumes. (See `quickstart.md` Scenario 2.)

- [x] T019 [US2] Create `ConsumerMapChangedEvent.java` record in `lib-consumer-map/src/main/java/uk/co/neversoft/consumermap/ConsumerMapChangedEvent.java` with fields: `previous` (ConsumerMapSnapshot), `current` (ConsumerMapSnapshot)
- [x] T020 [US2] Implement `ConsumerMapWatcher.java` `@ApplicationScoped` `@Startup` CDI bean in `lib-consumer-map/src/main/java/uk/co/neversoft/consumermap/ConsumerMapWatcher.java`: on startup check if hot-reload is enabled for the active profile (read `%quarkus.profile` from MicroProfile Config, compare against `hotReload.enabledEnvironments`); if enabled, start a daemon thread that polls the YAML file every `pollIntervalSeconds`; on valid change, call `ConsumerMapLoader`, replace the volatile snapshot in `ConsumerMapRegistry`, and fire a CDI `ConsumerMapChangedEvent`; on invalid file retain current snapshot and log a warning — depends on T008, T009, T019
- [x] T021 [US2] Write `ConsumerMapWatcherTest.java` in `lib-consumer-map/src/test/java/uk/co/neversoft/consumermap/ConsumerMapWatcherTest.java` covering: valid file change updates registry snapshot; invalid file change retains previous snapshot and logs warning; file deletion retains previous snapshot and logs warning; `ConsumerMapChangedEvent` is fired exactly once per valid change

**Checkpoint**: Hot-reload works in dev profile. Changing the YAML takes effect within 30 seconds without restart.

---

## Phase 5: User Story 3 — Environment-Scoped Hot-Loading (Priority: P3)

**Goal**: Hot-reload is active only in environments listed in `consumer-map.yml` `hot-reload.enabled-environments`. The watcher never starts in `prod`.

**Independent Test**: Run the full Docker Compose stack (`infra/docker-compose.yml`). Inspect `svc-audit` logs for the watcher disabled message. Edit `consumer-map.yml` on the host. Confirm after 60 seconds that no reload has occurred. (See `quickstart.md` Scenario 3.)

- [x] T022 [US3] Add `%prod.consumer-map.file=/config/consumer-map.yml` bind-mount entry to the four service definitions in `infra/docker-compose.yml` (volumes section: `./consumer-map.yml:/config/consumer-map.yml:ro`)
- [x] T023 [P] [US3] Add watcher profile-gating test cases to `lib-consumer-map/src/test/java/uk/co/neversoft/consumermap/ConsumerMapWatcherTest.java`: profile `prod` → watcher does not start; profile `dev` → watcher starts; profile not in enabled-environments → watcher does not start; empty enabled-environments list → watcher does not start in any profile
- [x] T024 [US3] Add integration test `ConsumerMapEnvGatingIT.java` in `integration-tests/src/test/java/uk/co/neversoft/it/ConsumerMapEnvGatingIT.java` verifying that with `quarkus.profile=prod` the watcher log line `"disabled"` appears and a YAML file change within 60 seconds produces no reload log

**Checkpoint**: All three user stories independently functional and tested. Hot-reload gating is verified at integration level.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T025 [P] Create `docs/ADR/adr-003-consumer-map.md` documenting the hot-reload mechanism decision, shared-library choice, and YAML-as-single-source-of-truth rationale (reference `research.md` decisions 1–5)
- [x] T026 Run all four validation scenarios from `specs/002-event-consumer-map/quickstart.md` and confirm pass criteria are met
- [x] T027 Run `mvn test` in `lib-consumer-map/` and confirm all unit tests pass
- [x] T028 [P] Run `mvn test` in `svc-validate/`, `svc-risk/`, and `svc-audit/` to confirm no regressions from the `isEnabled()` guard additions

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — start immediately
- **Phase 2 (Foundational)**: Depends on Phase 1 — **blocks all user story phases**
- **Phase 3 (US1)**: Depends on Phase 2 completion
- **Phase 4 (US2)**: Depends on Phase 2 + Phase 3 (needs `ConsumerMapRegistry` and `consumer-map.yml`)
- **Phase 5 (US3)**: Depends on Phase 4 (`ConsumerMapWatcher` must exist to add profile gating)
- **Phase 6 (Polish)**: Depends on Phases 3–5

### User Story Dependencies

- **US1 (P1)**: Unblocked after Foundational — no dependency on US2 or US3
- **US2 (P2)**: Requires US1 complete (watcher updates the same registry that US1 service beans read)
- **US3 (P3)**: Requires US2 complete (adds profile check to the watcher created in US2)

### Within Each Phase

- T003, T004, T005 (domain records) are fully parallel
- T006 depends on T003–T005
- T007 depends on T006
- T008 depends on T006, T007
- T009 depends on T007, T008
- T011, T012, T013 (pom + properties per service) are parallel
- T014, T015, T016 depend on T009 and their respective T011/T012/T013
- T017, T018 (unit tests for loader + registry) are parallel with T014–T016
- T019 before T020; T021 after T020
- T022, T023 are parallel within Phase 5

---

## Parallel Execution Examples

### Phase 2 (Foundational)

```
Parallel batch 1:  T003 ConsumerRegistration.java
                   T004 HotReloadConfig.java
                   T005 EventEntry.java
Sequential:        T006 ConsumerMap.java
                   T007 ConsumerMapSnapshot.java
                   T008 ConsumerMapLoader.java
                   T009 ConsumerMapRegistry.java
```

### Phase 3 (US1)

```
Sequential:        T010 Create consumer-map.yml
Parallel batch:    T011 svc-validate pom + properties
                   T012 svc-risk pom + properties
                   T013 svc-audit pom + properties
Sequential:        T014 DeclarationConsumer guard (after T011)
                   T015 ValidationConsumer guard (after T012)
                   T016 AuditConsumer guard (after T013)
Parallel batch:    T017 ConsumerMapLoaderTest.java
                   T018 ConsumerMapRegistryTest.java
```

---

## Implementation Strategy

### MVP (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL — blocks everything)
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: `consumer-map.yml` loads at startup; all three services compile and pass existing tests; `isEnabled()` returns correct values

### Incremental Delivery

1. **MVP**: Phases 1–3 → Static consumer map at startup, services check registry ✓
2. **+Hot-reload**: Phase 4 → YAML changes take effect within 30s in dev ✓
3. **+Environment gating**: Phase 5 → Prod is safe from accidental live reconfiguration ✓
4. **Polish**: Phase 6 → Documentation and regression verification ✓

---

## Notes

- `[P]` tasks operate on different files with no shared in-progress dependencies
- `consumer-map.file` property defaults to `../consumer-map.yml` for local/dev runs; `%prod` override uses the Docker Compose bind-mount path `/config/consumer-map.yml`
- The `isEnabled()` guard must be the first statement in each `@Incoming` method body, before any deserialization or side effects
- The watcher daemon thread must be marked `setDaemon(true)` to avoid blocking JVM shutdown
- `ConsumerMapRegistry`'s snapshot reference must be `volatile` to guarantee visibility across threads without locking
