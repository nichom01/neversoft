package uk.co.neversoft.risk.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uk.co.neversoft.risk.domain.RiskAssessment;
import uk.co.neversoft.risk.messaging.ValidationCompletedEvent;
import uk.co.neversoft.risk.service.RiskService;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RiskPayloadTest {

    @Test
    void payloadContainsRequiredFields() throws Exception {
        RiskAssessment assessment = buildAssessment();
        ValidationCompletedEvent event = buildEvent(assessment.declarationId, assessment.validationId);

        String json = RiskService.buildEventPayload(UUID.randomUUID(), event, assessment);
        var node = new ObjectMapper().readTree(json);

        assertEquals("risk.assessed",                  node.get("eventType").asText());
        assertEquals(assessment.id.toString(),          node.get("aggregateId").asText());
        assertEquals(assessment.declarationId.toString(), node.get("declarationId").asText());
        assertEquals(assessment.validationId.toString(),  node.get("validationId").asText());
        assertEquals(0.0,                               node.get("score").asDouble());
        assertEquals("LOW",                             node.get("band").asText());
        assertNotNull(                                  node.get("occurredAt"));
    }

    private static RiskAssessment buildAssessment() {
        RiskAssessment a = new RiskAssessment();
        a.id            = UUID.randomUUID();
        a.declarationId = UUID.randomUUID();
        a.validationId  = UUID.randomUUID();
        a.eventId       = UUID.randomUUID().toString();
        a.score         = 0.0;
        a.band          = "LOW";
        a.assessedAt    = Instant.now();
        return a;
    }

    private static ValidationCompletedEvent buildEvent(UUID declarationId, UUID validationId) {
        return new ValidationCompletedEvent(
                UUID.randomUUID().toString(),
                "validations.completed",
                validationId.toString(),
                declarationId.toString(),
                "PASSED",
                "",
                Instant.now().toString()
        );
    }
}
