package uk.co.neversoft.validate.component;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import uk.co.neversoft.validate.domain.Validation;
import uk.co.neversoft.validate.domain.ValidationOutcome;
import uk.co.neversoft.validate.messaging.DeclarationCreatedEvent;
import uk.co.neversoft.validate.service.ValidationService;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ValidationServiceTest {

    // Known UUIDs seeded by V1__create_customers.sql
    static final UUID KNOWN_CUSTOMER   = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    static final UUID UNKNOWN_CUSTOMER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Inject ValidationService service;
    @Inject EntityManager em;

    @Test
    void knownCustomer_writesPassedValidationAndOutbox() {
        String eventId = UUID.randomUUID().toString();
        service.validate(event(eventId, UUID.randomUUID(), KNOWN_CUSTOMER));

        Validation v = Validation.find("eventId", eventId).firstResult();
        assertNotNull(v);
        assertEquals(ValidationOutcome.PASSED, v.outcome);
        assertNull(v.failureReason);

        long outboxCount = em.createQuery(
                "SELECT COUNT(o) FROM OutboxEntry o WHERE o.aggregateId = :id", Long.class)
                .setParameter("id", v.id)
                .getSingleResult();
        assertEquals(1L, outboxCount);
    }

    @Test
    void unknownCustomer_writesFailedValidationWithReason() {
        String eventId = UUID.randomUUID().toString();
        service.validate(event(eventId, UUID.randomUUID(), UNKNOWN_CUSTOMER));

        Validation v = Validation.find("eventId", eventId).firstResult();
        assertNotNull(v);
        assertEquals(ValidationOutcome.FAILED, v.outcome);
        assertNotNull(v.failureReason);
        assertTrue(v.failureReason.contains(UNKNOWN_CUSTOMER.toString()));
    }

    @Test
    void duplicateEventId_isIdempotent() {
        String eventId = UUID.randomUUID().toString();
        DeclarationCreatedEvent e = event(eventId, UUID.randomUUID(), KNOWN_CUSTOMER);

        service.validate(e);
        service.validate(e);

        long count = Validation.find("eventId", eventId).count();
        assertEquals(1L, count);
    }

    private static DeclarationCreatedEvent event(String eventId, UUID declarationId, UUID customerId) {
        var payload = new DeclarationCreatedEvent.Payload(
                declarationId.toString(), customerId.toString());
        return new DeclarationCreatedEvent(
                eventId, "declarations.created",
                declarationId.toString(), Instant.now().toString(), payload);
    }
}
