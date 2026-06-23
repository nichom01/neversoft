package uk.co.neversoft.consumermap;

import java.nio.file.Path;
import java.time.Instant;

/** Immutable point-in-time snapshot held by ConsumerMapRegistry. Replaced atomically on reload. */
public record ConsumerMapSnapshot(
    Instant loadedAt,
    Path filePath,
    ConsumerMap map
) {}
