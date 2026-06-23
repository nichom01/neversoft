package uk.co.neversoft.risk.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import uk.co.neversoft.consumermap.ConsumerMapRegistry;
import uk.co.neversoft.risk.service.RiskService;

@ApplicationScoped
public class ValidationConsumer {

    @Inject
    RiskService service;

    @Inject
    ObjectMapper mapper;

    @Inject
    ConsumerMapRegistry registry;

    @Incoming("validations-completed")
    public void consume(String messageJson) throws Exception {
        if (!registry.isEnabled("validations-completed")) return;
        ValidationCompletedEvent event = mapper.readValue(messageJson, ValidationCompletedEvent.class);
        service.assess(event);
    }
}
