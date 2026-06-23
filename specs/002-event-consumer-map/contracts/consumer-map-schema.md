# Contract: consumer-map.yml Schema

**Feature**: 002-event-consumer-map  
**Version**: 1.0  
**Date**: 2026-06-23

---

## File Location

`<monorepo-root>/consumer-map.yml`

Configurable per-service via `application.properties`:

```properties
consumer-map.file=../consumer-map.yml
```

When running in Docker Compose, bind-mount the file into the container and set the path accordingly.

---

## Full Schema (annotated)

```yaml
# Required. Schema version; must be "1.0" for this release.
version: "1.0"

hot-reload:
  # Required. List of Quarkus profile names where file-watching is active.
  # Empty list disables hot-reload everywhere.
  enabled-environments:
    - local
    - dev
    - staging

  # Optional. How often (seconds) the watcher polls for file changes.
  # Minimum: 5. Default: 30.
  poll-interval-seconds: 30

# Required. At least one event entry.
# Keys are Kafka topic names exactly as they appear in the broker.
events:
  <topic-name>:
    # Required. At least one consumer per topic.
    consumers:
      - # Required. Human-readable name of the consuming service.
        service: <service-name>

        # Required. SmallRye channel name; matches mp.messaging.incoming.<channel>.
        # Format: lowercase letters, digits, hyphens; must start with a letter.
        channel: <channel-name>

        # Optional. Default: true.
        # Set false to pause routing for this consumer without removing the declaration.
        enabled: true | false
```

---

## Validation Rules

| Rule | Behaviour on violation |
|------|------------------------|
| `version` not `"1.0"` | Reject file; retain last valid snapshot; log error |
| `events` map is absent or empty | Reject file |
| `consumers` list is absent or empty for any event | Reject file |
| Duplicate `channel` within same event's consumer list | Reject file |
| `channel` does not match `[a-z][a-z0-9-]*` | Reject file |
| `poll-interval-seconds` < 5 | Reject file |
| File is missing at startup | Fatal startup failure |
| File becomes inaccessible after startup | Retain last valid snapshot; log warning every poll cycle |
| File is partially written mid-poll | Retry on next poll cycle; do not apply partial content |

---

## Worked Example (current monorepo baseline)

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

## Library API Contract

Services interact with the consumer map through the `ConsumerMapRegistry` CDI bean.

### ConsumerMapRegistry

```java
@ApplicationScoped
public interface ConsumerMapRegistry {

    /**
     * Returns true if the named channel is declared and enabled in the current snapshot.
     * Called by @Incoming handler methods to decide whether to process a message.
     */
    boolean isEnabled(String channelName);

    /**
     * Returns the active snapshot. Never null after startup.
     */
    ConsumerMapSnapshot activeSnapshot();
}
```

### Usage in a consumer service

```java
@ApplicationScoped
public class DeclarationConsumer {

    @Inject
    ConsumerMapRegistry registry;

    @Incoming("declarations-created")
    public void onDeclaration(String payload) {
        if (!registry.isEnabled("declarations-created")) {
            return;   // consumer disabled in current map; discard silently
        }
        // ... process payload
    }
}
```

### ConsumerMapChangedEvent (CDI)

Fired when the watcher detects a valid file change and loads a new snapshot.

```java
public record ConsumerMapChangedEvent(
    ConsumerMapSnapshot previous,
    ConsumerMapSnapshot current
) {}
```

Beans that need to react to topology changes (e.g. logging, metrics) can `@Observes` this event.

---

## Operational Notes

- **No message loss on reload**: in-flight messages already delivered by Kafka are processed against the snapshot active at delivery time. The new snapshot applies from the next polled check.
- **No duplicates**: the registry swap is atomic (volatile reference); no partial-state window.
- **Production safety**: if `quarkus.profile` is not in `enabled-environments`, the watcher never starts. The snapshot loaded at startup is permanent for the lifetime of the process.
