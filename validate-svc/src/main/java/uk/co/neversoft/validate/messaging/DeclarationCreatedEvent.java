package uk.co.neversoft.validate.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DeclarationCreatedEvent(
        String eventId,
        String eventType,
        String aggregateId,
        String occurredAt,
        Payload payload
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(String declarationId, String customerId) {}
}
