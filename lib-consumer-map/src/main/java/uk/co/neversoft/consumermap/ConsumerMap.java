package uk.co.neversoft.consumermap;

import java.util.Map;

/** Top-level object parsed from consumer-map.yml. */
public record ConsumerMap(
    String version,
    HotReloadConfig hotReload,
    Map<String, EventEntry> events
) {}
