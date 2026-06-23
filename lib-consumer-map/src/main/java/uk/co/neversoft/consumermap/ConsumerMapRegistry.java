package uk.co.neversoft.consumermap;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Holds the active ConsumerMapSnapshot and answers isEnabled() queries per message.
 * The snapshot reference is volatile so the watcher can swap it lock-free.
 */
@ApplicationScoped
public class ConsumerMapRegistry {

    private static final Logger LOG = Logger.getLogger(ConsumerMapRegistry.class);

    @Inject
    @ConfigProperty(name = "consumer-map.file")
    String filePath;

    private final ConsumerMapLoader loader = new ConsumerMapLoader();
    private volatile ConsumerMapSnapshot snapshot;

    @PostConstruct
    void init() {
        doInit(filePath);
    }

    /** Package-private entry point so unit tests can call it directly without CDI. */
    void doInit(String fp) {
        try {
            snapshot = loader.load(fp);
            LOG.infof("ConsumerMapRegistry: loaded %d event(s) from %s",
                snapshot.map().events().size(), fp);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load consumer map from: " + fp, e);
        }
    }

    /**
     * Returns true if {@code channelName} is declared and enabled in the current snapshot.
     * Returns false for an unknown channel.
     * This is a pure volatile read — no I/O, no locking.
     */
    public boolean isEnabled(String channelName) {
        ConsumerMapSnapshot snap = snapshot;
        for (EventEntry entry : snap.map().events().values()) {
            for (ConsumerRegistration reg : entry.consumers()) {
                if (channelName.equals(reg.channel())) {
                    return reg.isEffectivelyEnabled();
                }
            }
        }
        return false;
    }

    /** Returns the active snapshot. Never null after startup. */
    public ConsumerMapSnapshot activeSnapshot() {
        return snapshot;
    }

    /** Called by ConsumerMapWatcher after a valid reload. */
    void updateSnapshot(ConsumerMapSnapshot newSnapshot) {
        snapshot = newSnapshot;
    }
}
