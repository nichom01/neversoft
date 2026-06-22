package uk.co.neversoft.audit.component;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import uk.co.neversoft.audit.domain.AuditEntry;
import uk.co.neversoft.audit.messaging.IncomingEvent;
import uk.co.neversoft.audit.service.AuditService;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AuditServiceTest {

    @Inject AuditService service;

    @Test
    void declarationEvent_writesAuditRow() {
        String eventId = UUID.randomUUID().toString();
        String raw = buildRaw(eventId, "declarations.created");
        service.record("declarations.created", event(eventId, "declarations.created"), raw);

        AuditEntry entry = AuditEntry.find("eventId", eventId).firstResult();
        assertNotNull(entry);
        assertEquals("declarations.created", entry.topic);
        assertEquals("declarations.created", entry.eventType);
        assertEquals(raw, entry.rawPayload);
    }

    @Test
    void validationEvent_writesAuditRow() {
        String eventId = UUID.randomUUID().toString();
        service.record("validations.completed", event(eventId, "validations.completed"),
                buildRaw(eventId, "validations.completed"));

        AuditEntry entry = AuditEntry.find("eventId", eventId).firstResult();
        assertNotNull(entry);
        assertEquals("validations.completed", entry.topic);
    }

    @Test
    void riskEvent_writesAuditRow() {
        String eventId = UUID.randomUUID().toString();
        service.record("risk.assessed", event(eventId, "risk.assessed"),
                buildRaw(eventId, "risk.assessed"));

        AuditEntry entry = AuditEntry.find("eventId", eventId).firstResult();
        assertNotNull(entry);
        assertEquals("risk.assessed", entry.topic);
    }

    @Test
    void duplicateEvent_isIdempotent() {
        String eventId = UUID.randomUUID().toString();
        IncomingEvent e = event(eventId, "declarations.created");
        String raw = buildRaw(eventId, "declarations.created");

        service.record("declarations.created", e, raw);
        service.record("declarations.created", e, raw);

        long count = AuditEntry.find("eventId", eventId).count();
        assertEquals(1L, count);
    }

    private static IncomingEvent event(String eventId, String eventType) {
        return new IncomingEvent(eventId, eventType, UUID.randomUUID().toString());
    }

    private static String buildRaw(String eventId, String eventType) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + eventType + "\",\"aggregateId\":\"" + UUID.randomUUID() + "\"}";
    }
}
