package uk.co.neversoft.risk.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ValidationCompletedEvent(
        String eventId,
        String eventType,
        String aggregateId,
        String declarationId,
        String outcome,
        String failureReason,
        String occurredAt
) {}
