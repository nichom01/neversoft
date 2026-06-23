# Feature Specification: Event Consumer Map

**Feature Branch**: `002-event-consumer-map`

**Created**: 2026-06-23

**Status**: Draft

**Input**: User description: "I want a single yaml file which i can map the consumers of events created on the message bus and be able to hot load this in a particular environment"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Define Event Consumer Mappings in YAML (Priority: P1)

An operator or developer wants a single, human-readable configuration file that explicitly declares which consumers receive which events from the message bus. They edit the YAML file to add, remove, or update consumer registrations without touching service code.

**Why this priority**: This is the core capability — without a mapping file there is nothing to hot-load. It establishes the schema and contract that all other functionality depends on.

**Independent Test**: Can be fully tested by writing a YAML file, loading it into the system, and verifying the correct consumers are registered for the correct events — without any hot-loading involved.

**Acceptance Scenarios**:

1. **Given** a YAML file containing event-to-consumer mappings, **When** the system loads the file, **Then** each consumer is registered for exactly the events listed against it
2. **Given** an event appears in the YAML, **When** that event is published to the message bus, **Then** all consumers mapped to that event receive it
3. **Given** a consumer is removed from an event mapping, **When** the YAML is reloaded, **Then** that consumer no longer receives events of that type
4. **Given** an invalid or malformed YAML file, **When** the system attempts to load it, **Then** the load is rejected with a clear, actionable error message and the previous valid mappings remain active

---

### User Story 2 - Hot-Load Consumer Mappings in a Target Environment (Priority: P2)

An operator updates the consumer mapping YAML file in a running environment and wants those changes to take effect without restarting any services or causing message loss.

**Why this priority**: Hot-loading is the key operational benefit. Without it, mapping changes require downtime, which eliminates the operational advantage of a centralised config file.

**Independent Test**: Can be fully tested by modifying the YAML file while the system is running, waiting for the reload interval, and confirming the new mappings are active — verifiable through consumer subscription state.

**Acceptance Scenarios**:

1. **Given** the system is running with an active consumer map, **When** the YAML file is updated and saved, **Then** the new mappings become active within the configured reload interval without any service restart
2. **Given** the YAML is updated to add a new consumer, **When** the reload occurs, **Then** that consumer begins receiving matching events from the point of reload onward, with no events lost
3. **Given** the YAML is updated with an invalid change, **When** the reload is triggered, **Then** the system rejects the invalid file, retains the last valid mappings, and records a warning that is visible to the operator
4. **Given** a reload is in progress, **When** an event is published to the bus, **Then** it is handled by either the old or new mappings with no duplication or loss

---

### User Story 3 - Environment-Scoped Hot-Loading (Priority: P3)

A developer wants hot-loading to be enabled in non-production environments (e.g. local, development, staging) but disabled or restricted in production, to prevent accidental live reconfiguration.

**Why this priority**: Controlling which environments permit hot-loading is a safety requirement. Production consumers should not silently change at runtime; all environments should still benefit from the same YAML schema.

**Independent Test**: Can be fully tested by configuring two environments — one with hot-loading enabled, one without — and verifying that a YAML file update triggers a reload only in the enabled environment.

**Acceptance Scenarios**:

1. **Given** the system is running in an environment where hot-loading is enabled, **When** the YAML file changes, **Then** the new mappings are automatically reloaded
2. **Given** the system is running in an environment where hot-loading is disabled, **When** the YAML file changes, **Then** no automatic reload occurs and a restart is required for changes to take effect
3. **Given** an operator checks the running configuration, **When** they inspect the system, **Then** they can clearly see whether hot-loading is active in the current environment

---

### Edge Cases

- What happens when the YAML file is deleted or becomes inaccessible after initial load?
- What happens if two updates to the YAML file arrive within the same reload interval (only the latest should apply)?
- What if a consumer referenced in the YAML is not currently registered or reachable — should the mapping still load?
- How does the system behave when the YAML file is partially written (mid-save) when a reload is triggered?
- What happens when the same consumer appears multiple times under the same event in the YAML?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST load event-to-consumer mappings from a single designated YAML file
- **FR-002**: System MUST validate the YAML file structure and content on every load, rejecting files that do not conform to the defined schema
- **FR-003**: System MUST support mapping one event type to one or more consumers
- **FR-004**: System MUST support mapping one consumer to one or more event types
- **FR-005**: System MUST retain the last successfully loaded mappings if a subsequent reload attempt fails validation
- **FR-006**: System MUST provide a clear error message when a YAML file fails to load, identifying the specific issue
- **FR-007**: System MUST support hot-loading — automatically reloading mappings from the YAML file at a configurable interval — when running in an environment where hot-loading is enabled
- **FR-008**: System MUST support designating specific environments where hot-loading is permitted, with all other environments requiring a restart for changes to apply
- **FR-009**: System MUST ensure no events are lost or duplicated during a hot-reload transition between mapping states
- **FR-010**: System MUST log all mapping load events (initial load, successful reload, failed reload) in a way that is visible to operators

### Key Entities

- **Event**: A typed message published to the message bus, identified by a topic or event-type key
- **Consumer**: A named service or component that subscribes to and processes events; identified by a unique name in the YAML
- **Mapping**: A declared relationship between an event type and one or more consumers; the mapping is the unit of configuration in the YAML file
- **Consumer Map**: The full set of mappings defined in the YAML file at any point in time; the active consumer map is what the system enforces
- **Environment**: A named deployment context (e.g. `local`, `dev`, `staging`, `production`) that controls whether hot-loading is permitted

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Operators can update consumer mappings and have changes active within 60 seconds in hot-load-enabled environments, with no service restarts required
- **SC-002**: An invalid YAML update never disrupts active consumers — the previous valid mappings remain in effect within 5 seconds of a failed reload attempt
- **SC-003**: Zero events are lost or duplicated across a hot-reload transition under normal operating conditions
- **SC-004**: Operators can determine the currently active consumer map and the last reload status without accessing system internals or restarting services
- **SC-005**: A developer unfamiliar with the system can read the YAML file and understand all active consumer mappings without additional documentation

## Assumptions

- The message bus is already operational and event types are defined elsewhere; this feature only maps consumers to existing events
- Multiple consumers can legitimately subscribe to the same event type (fan-out pattern)
- Hot-loading will be enabled in `local`, `dev`, and `staging` environments by default; `production` will require a restart
- The YAML file lives in a location accessible to the running system (local filesystem or mounted volume); remote file sources are out of scope for this version
- The reload interval for hot-loading will be configurable (defaulting to 30 seconds) rather than using filesystem watch events, to avoid platform-specific dependencies
- Consumer names in the YAML are stable identifiers; renaming a consumer in the YAML is treated as removing the old and adding a new one
- The YAML file is managed by operators or CI/CD pipelines; access control on the file itself is out of scope for this feature
