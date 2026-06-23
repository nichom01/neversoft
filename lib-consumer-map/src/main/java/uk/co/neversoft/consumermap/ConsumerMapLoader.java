package uk.co.neversoft.consumermap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Parses and validates consumer-map.yml, returning an immutable snapshot on success. */
public class ConsumerMapLoader {

    private static final Pattern CHANNEL_PATTERN = Pattern.compile("[a-z][a-z0-9-]*");
    private static final String SUPPORTED_VERSION = "1.0";

    private final YAMLMapper mapper;

    public ConsumerMapLoader() {
        mapper = new YAMLMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public ConsumerMapSnapshot load(String filePath) throws ConsumerMapValidationException, IOException {
        Path path = Path.of(filePath).toAbsolutePath();
        if (!Files.exists(path)) {
            throw new ConsumerMapValidationException("consumer-map file not found: " + path);
        }
        ConsumerMap map = mapper.readValue(path.toFile(), ConsumerMap.class);
        validate(map, path);
        return new ConsumerMapSnapshot(Instant.now(), path, map);
    }

    private void validate(ConsumerMap map, Path path) throws ConsumerMapValidationException {
        // Rule 1: version must be "1.0"
        if (!SUPPORTED_VERSION.equals(map.version())) {
            throw new ConsumerMapValidationException(
                "version must be \"" + SUPPORTED_VERSION + "\", got: " + map.version() + " in " + path);
        }

        // Rule 2: events map must be present and non-empty
        if (map.events() == null || map.events().isEmpty()) {
            throw new ConsumerMapValidationException("events map must not be empty in " + path);
        }

        for (var entry : map.events().entrySet()) {
            String topic = entry.getKey();
            EventEntry eventEntry = entry.getValue();

            // Rule 3: consumers list must be present and non-empty for each event
            List<ConsumerRegistration> consumers = eventEntry == null ? null : eventEntry.consumers();
            if (consumers == null || consumers.isEmpty()) {
                throw new ConsumerMapValidationException(
                    "consumers list is empty for topic \"" + topic + "\" in " + path);
            }

            Set<String> seenChannels = new HashSet<>();
            for (ConsumerRegistration reg : consumers) {
                // Rule 5: channel must match [a-z][a-z0-9-]*
                if (reg.channel() == null || !CHANNEL_PATTERN.matcher(reg.channel()).matches()) {
                    throw new ConsumerMapValidationException(
                        "channel \"" + reg.channel() + "\" does not match [a-z][a-z0-9-]* in topic \"" + topic + "\"");
                }

                // Rule 4: no duplicate channels within same event
                if (!seenChannels.add(reg.channel())) {
                    throw new ConsumerMapValidationException(
                        "duplicate channel \"" + reg.channel() + "\" in topic \"" + topic + "\"");
                }
            }
        }

        // Rule 6: poll-interval-seconds must be >= 5 when specified
        HotReloadConfig hotReload = map.hotReload();
        if (hotReload != null && hotReload.pollIntervalSeconds() != null
                && hotReload.pollIntervalSeconds() < 5) {
            throw new ConsumerMapValidationException(
                "poll-interval-seconds must be >= 5, got: " + hotReload.pollIntervalSeconds() + " in " + path);
        }
    }
}
