package uk.co.neversoft.consumermap;

/**
 * One declared mapping of a Kafka topic to a specific service channel.
 * {@code enabled} is nullable: null means the default (true).
 */
public record ConsumerRegistration(
    String service,
    String channel,
    Boolean enabled
) {
    public boolean isEffectivelyEnabled() {
        return enabled == null || enabled;
    }
}
