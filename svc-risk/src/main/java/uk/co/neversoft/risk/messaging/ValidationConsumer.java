package uk.co.neversoft.risk.messaging;

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
import uk.co.neversoft.risk.service.RiskService;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class ValidationConsumer {

    private static final Logger LOG = Logger.getLogger(ValidationConsumer.class);
    private static final String CHANNEL = "validations-completed";
    private static final String TOPIC = "validations.completed";

    @Inject
    RiskService service;

    @Inject
    ObjectMapper mapper;

    @Inject
    ConsumerMapRegistry registry;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    @Channel("validations-completed-dlq")
    Emitter<String> deadLetterEmitter;

    @Incoming(CHANNEL)
    public void consume(List<Message<String>> batch) throws Exception {
        if (!registry.isEnabled(CHANNEL)) return;

        int successCount = 0;
        int deadLetteredCount = 0;
        for (Message<String> message : batch) {
            String messageJson = message.getPayload();
            try {
                ValidationCompletedEvent event = mapper.readValue(messageJson, ValidationCompletedEvent.class);
                service.assess(event);
                successCount++;
            } catch (Exception e) {
                deadLetterEmitter.send(mapper.writeValueAsString(new DeadLetterEnvelope(
                        messageJson, TOPIC, e.getMessage(), Instant.now().toString(), "svc-risk")));
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
