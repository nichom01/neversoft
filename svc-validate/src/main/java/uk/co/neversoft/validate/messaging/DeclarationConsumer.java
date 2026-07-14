package uk.co.neversoft.validate.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;
import uk.co.neversoft.consumermap.ConsumerMapRegistry;
import uk.co.neversoft.validate.service.ValidationService;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class DeclarationConsumer {

    private static final Logger LOG = Logger.getLogger(DeclarationConsumer.class);
    private static final String CHANNEL = "declarations-created";
    private static final String TOPIC = "declarations.created";

    @Inject
    ValidationService service;

    @Inject
    ObjectMapper mapper;

    @Inject
    ConsumerMapRegistry registry;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    @Channel("declarations-created-dlq")
    Emitter<String> deadLetterEmitter;

    @Incoming(CHANNEL)
    public void consume(List<Message<String>> batch) throws Exception {
        if (!registry.isEnabled(CHANNEL)) return;

        int successCount = 0;
        int deadLetteredCount = 0;
        for (Message<String> message : batch) {
            String messageJson = message.getPayload();
            try {
                DeclarationCreatedEvent event = mapper.readValue(messageJson, DeclarationCreatedEvent.class);
                service.validate(event);
                successCount++;
            } catch (Exception e) {
                deadLetterEmitter.send(mapper.writeValueAsString(new DeadLetterEnvelope(
                        messageJson, TOPIC, e.getMessage(), Instant.now().toString(), "svc-validate")));
                deadLetteredCount++;
            }
            message.ack();
        }

        meterRegistry.summary("kafka.batch.size", "channel", CHANNEL).record(batch.size());
        meterRegistry.counter("kafka.batch.records.success", "channel", CHANNEL).increment(successCount);
        meterRegistry.counter("kafka.batch.records.deadlettered", "channel", CHANNEL).increment(deadLetteredCount);

        LOG.infof("batch.processed channel=%s batchSize=%d success=%d deadLettered=%d",
                CHANNEL, batch.size(), successCount, deadLetteredCount);
    }
}
