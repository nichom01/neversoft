package uk.co.neversoft.risk.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeadLetterEnvelope(
        String originalPayload,
        String originalTopic,
        String failureReason,
        String failedAt,
        String serviceName) {}
