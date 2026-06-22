package uk.co.neversoft.audit.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record IncomingEvent(String eventId, String eventType, String aggregateId) {}
