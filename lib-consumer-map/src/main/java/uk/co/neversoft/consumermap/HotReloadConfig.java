package uk.co.neversoft.consumermap;

import java.util.List;

/**
 * Controls file-watcher behaviour. {@code pollIntervalSeconds} is nullable: null means the default (30).
 */
public record HotReloadConfig(
    List<String> enabledEnvironments,
    Integer pollIntervalSeconds
) {
    public static final int DEFAULT_POLL_INTERVAL = 30;

    public int effectivePollInterval() {
        return pollIntervalSeconds != null ? pollIntervalSeconds : DEFAULT_POLL_INTERVAL;
    }
}
