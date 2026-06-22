package uk.co.neversoft.declare.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import uk.co.neversoft.declare.api.CreateDeclarationRequest;
import uk.co.neversoft.declare.domain.Declaration;
import uk.co.neversoft.declare.domain.DeclarationStatus;
import uk.co.neversoft.declare.domain.OutboxEntry;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class DeclarationService {

    @Inject
    ObjectMapper mapper;

    @Transactional
    public DeclarationResult create(CreateDeclarationRequest request) {
        Declaration existing = Declaration.find("idempotencyKey", request.idempotencyKey).firstResult();
        if (existing != null) {
            return new DeclarationResult(existing.id, false);
        }

        Declaration declaration = new Declaration();
        declaration.id = UUID.randomUUID();
        declaration.customerId = request.customerId;
        declaration.payload = toJson(request.payload);
        declaration.status = DeclarationStatus.PENDING;
        declaration.idempotencyKey = request.idempotencyKey;
        declaration.createdAt = Instant.now();
        declaration.persist();

        OutboxEntry outbox = new OutboxEntry();
        outbox.id = UUID.randomUUID();
        outbox.aggregateType = "declarations.created";
        outbox.aggregateId = declaration.id;
        outbox.eventType = "declarations.created";
        outbox.payload = buildEventPayload(outbox.id, declaration);
        outbox.createdAt = declaration.createdAt;
        outbox.persist();

        return new DeclarationResult(declaration.id, true);
    }

    // package-private + static so OutboxPayloadTest can call it without CDI
    static String buildEventPayload(UUID eventId, Declaration declaration) {
        try {
            var m = new ObjectMapper();
            m.findAndRegisterModules();
            return m.writeValueAsString(Map.of(
                    "eventId", eventId.toString(),
                    "eventType", "declarations.created",
                    "aggregateId", declaration.id.toString(),
                    "occurredAt", declaration.createdAt.toString(),
                    "payload", Map.of(
                            "declarationId", declaration.id.toString(),
                            "customerId", declaration.customerId.toString()
                    )
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise event payload", e);
        }
    }

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid payload", e);
        }
    }
}
