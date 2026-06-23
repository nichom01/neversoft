package uk.co.neversoft.validate.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import uk.co.neversoft.consumermap.ConsumerMapRegistry;
import uk.co.neversoft.validate.service.ValidationService;

@ApplicationScoped
public class DeclarationConsumer {

    @Inject
    ValidationService service;

    @Inject
    ObjectMapper mapper;

    @Inject
    ConsumerMapRegistry registry;

    @Incoming("declarations-created")
    public void consume(String messageJson) throws Exception {
        if (!registry.isEnabled("declarations-created")) return;
        DeclarationCreatedEvent event = mapper.readValue(messageJson, DeclarationCreatedEvent.class);
        service.validate(event);
    }
}
