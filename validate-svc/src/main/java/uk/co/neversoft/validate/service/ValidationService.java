package uk.co.neversoft.validate.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.kie.api.runtime.KieSession;
import uk.co.neversoft.validate.domain.Customer;
import uk.co.neversoft.validate.domain.OutboxEntry;
import uk.co.neversoft.validate.domain.Validation;
import uk.co.neversoft.validate.messaging.DeclarationCreatedEvent;
import uk.co.neversoft.validate.rules.CustomerFact;
import uk.co.neversoft.validate.rules.DeclarationFact;
import uk.co.neversoft.validate.rules.RuleEngineService;

import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ValidationService {

    private static final Logger LOG = Logger.getLogger(ValidationService.class);

    @Inject
    RuleEngineService ruleEngine;

    @Inject
    ObjectMapper mapper;

    @Transactional
    public void validate(DeclarationCreatedEvent event) {
        if (Validation.find("eventId", event.eventId()).count() > 0) {
            return;
        }

        UUID customerId = UUID.fromString(event.payload().customerId());
        DeclarationFact fact = runRules(customerId);

        Validation validation = new Validation();
        validation.id = UUID.randomUUID();
        validation.declarationId = UUID.fromString(event.payload().declarationId());
        validation.eventId = event.eventId();
        validation.outcome = fact.getOutcome();
        validation.failureReason = fact.getFailureReason();
        validation.rulesApplied = "[\"Customer must exist\"]";
        validation.validatedAt = Instant.now();
        validation.persist();

        OutboxEntry outbox = new OutboxEntry();
        outbox.id = UUID.randomUUID();
        outbox.aggregateType = "validations.completed";
        outbox.aggregateId = validation.id;
        outbox.eventType = "validations.completed";
        outbox.payload = buildEventPayload(outbox.id, event, validation);
        outbox.createdAt = validation.validatedAt;
        outbox.persist();

        LOG.infof("validations.completed declarationId=%s outcome=%s eventId=%s",
                validation.declarationId, validation.outcome, outbox.id);
    }

    private DeclarationFact runRules(UUID customerId) {
        KieSession session = ruleEngine.newSession();
        try {
            Customer.<Customer>listAll().forEach(c -> session.insert(new CustomerFact(c.id)));
            DeclarationFact fact = new DeclarationFact(customerId);
            session.insert(fact);
            session.fireAllRules();
            return fact;
        } finally {
            session.dispose();
        }
    }

    static String buildEventPayload(UUID eventId, DeclarationCreatedEvent source, Validation validation) {
        try {
            var m = new ObjectMapper();
            m.findAndRegisterModules();
            return m.writeValueAsString(Map.of(
                    "eventId", eventId.toString(),
                    "eventType", "validations.completed",
                    "aggregateId", validation.id.toString(),
                    "declarationId", validation.declarationId.toString(),
                    "outcome", validation.outcome.name(),
                    "failureReason", validation.failureReason != null ? validation.failureReason : "",
                    "occurredAt", validation.validatedAt.toString(),
                    "payload", Map.of()
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise validation event payload", e);
        }
    }
}
