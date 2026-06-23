package uk.co.neversoft.consumermap;

/** CDI event fired when the watcher detects a valid file change and loads a new snapshot. */
public record ConsumerMapChangedEvent(
    ConsumerMapSnapshot previous,
    ConsumerMapSnapshot current
) {}
