package uk.co.neversoft.consumermap;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

/**
 * Polling file-watcher that runs only in profiles listed in hot-reload.enabled-environments.
 * On a valid file change it atomically replaces the registry snapshot and fires a CDI event.
 * On any error it retains the last valid snapshot and logs a warning.
 */
@ApplicationScoped
@Startup
public class ConsumerMapWatcher {

    private static final Logger LOG = Logger.getLogger(ConsumerMapWatcher.class);

    @Inject
    ConsumerMapRegistry registry;

    @Inject
    Event<ConsumerMapChangedEvent> changedEvent;

    @Inject
    @ConfigProperty(name = "quarkus.profile", defaultValue = "prod")
    String activeProfile;

    private volatile Instant lastObservedModTime;

    @PostConstruct
    void start() {
        ConsumerMapSnapshot initial = registry.activeSnapshot();
        HotReloadConfig hotReload = initial.map().hotReload();

        if (hotReload == null
                || hotReload.enabledEnvironments() == null
                || !hotReload.enabledEnvironments().contains(activeProfile)) {
            LOG.infof("ConsumerMapWatcher disabled (profile=%s not in enabled-environments)", activeProfile);
            return;
        }

        Path filePath = initial.filePath();
        lastObservedModTime = readModTime(filePath);

        int pollInterval = hotReload.effectivePollInterval();
        LOG.infof("ConsumerMapWatcher started (profile=%s, poll-interval=%ds)", activeProfile, pollInterval);

        Thread thread = new Thread(() -> pollLoop(filePath, pollInterval));
        thread.setDaemon(true);
        thread.setName("consumer-map-watcher");
        thread.start();
    }

    private void pollLoop(Path filePath, int pollIntervalSeconds) {
        ConsumerMapLoader loader = new ConsumerMapLoader();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(pollIntervalSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            checkForChanges(loader, filePath);
        }
    }

    private void checkForChanges(ConsumerMapLoader loader, Path filePath) {
        if (!Files.exists(filePath)) {
            LOG.warnf("ConsumerMapWatcher: file not accessible at %s — retaining last valid snapshot", filePath);
            return;
        }

        Instant currentModTime = readModTime(filePath);
        if (currentModTime == null || (lastObservedModTime != null && !currentModTime.isAfter(lastObservedModTime))) {
            return;
        }

        try {
            ConsumerMapSnapshot oldSnapshot = registry.activeSnapshot();
            ConsumerMapSnapshot newSnapshot = loader.load(filePath.toString());
            lastObservedModTime = currentModTime;
            registry.updateSnapshot(newSnapshot);
            changedEvent.fire(new ConsumerMapChangedEvent(oldSnapshot, newSnapshot));
            LOG.infof("ConsumerMapWatcher: reload detected — snapshot updated from %s", filePath);
        } catch (ConsumerMapValidationException e) {
            LOG.warnf("ConsumerMapWatcher: invalid file at %s — retaining last valid snapshot. Reason: %s",
                filePath, e.getMessage());
        } catch (IOException e) {
            LOG.warnf("ConsumerMapWatcher: failed to read %s — retaining last valid snapshot. Error: %s",
                filePath, e.getMessage());
        }
    }

    private Instant readModTime(Path path) {
        try {
            FileTime ft = Files.getLastModifiedTime(path);
            return ft.toInstant();
        } catch (IOException e) {
            return null;
        }
    }
}
