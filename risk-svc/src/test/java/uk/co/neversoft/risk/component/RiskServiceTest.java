package uk.co.neversoft.risk.component;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import uk.co.neversoft.risk.domain.RiskAssessment;
import uk.co.neversoft.risk.messaging.ValidationCompletedEvent;
import uk.co.neversoft.risk.service.RiskService;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RiskServiceTest {

    @Inject RiskService service;
    @Inject EntityManager em;

    @Test
    void passedEvent_writesAssessmentAndOutbox() {
        String eventId = UUID.randomUUID().toString();
        service.assess(passedEvent(eventId));

        RiskAssessment a = RiskAssessment.find("eventId", eventId).firstResult();
        assertNotNull(a);
        assertEquals(0.0,   a.score);
        assertEquals("LOW", a.band);

        long outboxCount = em.createQuery(
                "SELECT COUNT(o) FROM OutboxEntry o WHERE o.aggregateId = :id", Long.class)
                .setParameter("id", a.id)
                .getSingleResult();
        assertEquals(1L, outboxCount);
    }

    @Test
    void failedEvent_writesNoRows() {
        String eventId = UUID.randomUUID().toString();
        service.assess(failedEvent(eventId));

        long count = RiskAssessment.find("eventId", eventId).count();
        assertEquals(0L, count);
    }

    @Test
    void duplicatePassedEvent_isIdempotent() {
        String eventId = UUID.randomUUID().toString();
        ValidationCompletedEvent e = passedEvent(eventId);

        service.assess(e);
        service.assess(e);

        long count = RiskAssessment.find("eventId", eventId).count();
        assertEquals(1L, count);
    }

    private static ValidationCompletedEvent passedEvent(String eventId) {
        return new ValidationCompletedEvent(
                eventId,
                "validations.completed",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "PASSED",
                "",
                Instant.now().toString()
        );
    }

    private static ValidationCompletedEvent failedEvent(String eventId) {
        return new ValidationCompletedEvent(
                eventId,
                "validations.completed",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "FAILED",
                "Customer not found: abc",
                Instant.now().toString()
        );
    }
}
