package uk.co.neversoft.audit.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import uk.co.neversoft.audit.domain.AuditEntry;
import uk.co.neversoft.audit.messaging.IncomingEvent;

import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class AuditService {

    @Transactional
    public void record(String topic, IncomingEvent event, String rawPayload) {
        if (AuditEntry.find("eventId", event.eventId()).count() > 0) {
            return;
        }

        AuditEntry entry = new AuditEntry();
        entry.id          = UUID.randomUUID();
        entry.eventId     = event.eventId();
        entry.topic       = topic;
        entry.eventType   = event.eventType();
        entry.aggregateId = event.aggregateId();
        entry.rawPayload  = rawPayload;
        entry.receivedAt  = Instant.now();
        entry.persist();
    }
}
