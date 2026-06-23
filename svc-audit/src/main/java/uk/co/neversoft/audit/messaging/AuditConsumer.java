package uk.co.neversoft.audit.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import uk.co.neversoft.audit.service.AuditService;
import uk.co.neversoft.consumermap.ConsumerMapRegistry;

@ApplicationScoped
public class AuditConsumer {

    @Inject
    AuditService service;

    @Inject
    ObjectMapper mapper;

    @Inject
    ConsumerMapRegistry registry;

    @Incoming("audit-declarations")
    public void onDeclaration(String messageJson) throws Exception {
        if (!registry.isEnabled("audit-declarations")) return;
        IncomingEvent event = mapper.readValue(messageJson, IncomingEvent.class);
        service.record("declarations.created", event, messageJson);
    }

    @Incoming("audit-validations")
    public void onValidation(String messageJson) throws Exception {
        if (!registry.isEnabled("audit-validations")) return;
        IncomingEvent event = mapper.readValue(messageJson, IncomingEvent.class);
        service.record("validations.completed", event, messageJson);
    }

    @Incoming("audit-risk")
    public void onRisk(String messageJson) throws Exception {
        if (!registry.isEnabled("audit-risk")) return;
        IncomingEvent event = mapper.readValue(messageJson, IncomingEvent.class);
        service.record("risk.assessed", event, messageJson);
    }
}
