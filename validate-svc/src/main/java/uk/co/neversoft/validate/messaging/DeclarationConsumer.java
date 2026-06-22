package uk.co.neversoft.validate.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import uk.co.neversoft.validate.service.ValidationService;

@ApplicationScoped
public class DeclarationConsumer {

    @Inject
    ValidationService service;

    @Inject
    ObjectMapper mapper;

    @Incoming("declarations-created")
    public void consume(String messageJson) throws Exception {
        DeclarationCreatedEvent event = mapper.readValue(messageJson, DeclarationCreatedEvent.class);
        service.validate(event);
    }
}
