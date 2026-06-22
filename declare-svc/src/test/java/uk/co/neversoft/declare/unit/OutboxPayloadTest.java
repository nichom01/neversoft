package uk.co.neversoft.declare.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uk.co.neversoft.declare.domain.Declaration;
import uk.co.neversoft.declare.domain.DeclarationStatus;
import uk.co.neversoft.declare.service.DeclarationService;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OutboxPayloadTest {

    @Test
    void payloadContainsAllRequiredFields() throws Exception {
        Declaration d = new Declaration();
        d.id = UUID.randomUUID();
        d.customerId = UUID.randomUUID();
        d.createdAt = Instant.now();
        d.status = DeclarationStatus.PENDING;
        d.idempotencyKey = "test-key";

        UUID eventId = UUID.randomUUID();
        String json = DeclarationService.buildEventPayload(eventId, d);

        var node = new ObjectMapper().readTree(json);

        assertEquals(eventId.toString(),    node.get("eventId").asText());
        assertEquals("declarations.created", node.get("eventType").asText());
        assertEquals(d.id.toString(),        node.get("aggregateId").asText());
        assertNotNull(                        node.get("occurredAt"));
        assertEquals(d.id.toString(),         node.at("/payload/declarationId").asText());
        assertEquals(d.customerId.toString(), node.at("/payload/customerId").asText());
    }

    @Test
    void payloadIsValidJson() {
        Declaration d = new Declaration();
        d.id = UUID.randomUUID();
        d.customerId = UUID.randomUUID();
        d.createdAt = Instant.now();
        d.status = DeclarationStatus.PENDING;
        d.idempotencyKey = "test-key-2";

        String json = DeclarationService.buildEventPayload(UUID.randomUUID(), d);

        assertNotNull(json);
        assertTrue(json.startsWith("{"));
    }
}
