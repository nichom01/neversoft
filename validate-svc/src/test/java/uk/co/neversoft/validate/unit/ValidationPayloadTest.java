package uk.co.neversoft.validate.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uk.co.neversoft.validate.domain.Validation;
import uk.co.neversoft.validate.domain.ValidationOutcome;
import uk.co.neversoft.validate.messaging.DeclarationCreatedEvent;
import uk.co.neversoft.validate.service.ValidationService;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ValidationPayloadTest {

    @Test
    void passedPayloadContainsRequiredFields() throws Exception {
        Validation v = buildValidation(ValidationOutcome.PASSED, null);
        DeclarationCreatedEvent event = buildEvent(v.declarationId);

        String json = ValidationService.buildEventPayload(UUID.randomUUID(), event, v);
        var node = new ObjectMapper().readTree(json);

        assertEquals("validations.completed", node.get("eventType").asText());
        assertEquals(v.id.toString(),          node.get("aggregateId").asText());
        assertEquals(v.declarationId.toString(), node.get("declarationId").asText());
        assertEquals("PASSED",                 node.get("outcome").asText());
        assertNotNull(                          node.get("occurredAt"));
    }

    @Test
    void failedPayloadIncludesFailureReason() throws Exception {
        Validation v = buildValidation(ValidationOutcome.FAILED, "Customer not found: abc");
        DeclarationCreatedEvent event = buildEvent(v.declarationId);

        String json = ValidationService.buildEventPayload(UUID.randomUUID(), event, v);
        var node = new ObjectMapper().readTree(json);

        assertEquals("FAILED",               node.get("outcome").asText());
        assertEquals("Customer not found: abc", node.get("failureReason").asText());
    }

    private static Validation buildValidation(ValidationOutcome outcome, String failureReason) {
        Validation v = new Validation();
        v.id = UUID.randomUUID();
        v.declarationId = UUID.randomUUID();
        v.eventId = UUID.randomUUID().toString();
        v.outcome = outcome;
        v.failureReason = failureReason;
        v.validatedAt = Instant.now();
        return v;
    }

    private static DeclarationCreatedEvent buildEvent(UUID declarationId) {
        var payload = new DeclarationCreatedEvent.Payload(declarationId.toString(), UUID.randomUUID().toString());
        return new DeclarationCreatedEvent(UUID.randomUUID().toString(), "declarations.created",
                UUID.randomUUID().toString(), Instant.now().toString(), payload);
    }
}
