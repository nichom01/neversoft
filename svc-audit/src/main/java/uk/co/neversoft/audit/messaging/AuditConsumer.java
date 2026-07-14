package uk.co.neversoft.audit.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;
import uk.co.neversoft.audit.service.AuditService;
import uk.co.neversoft.consumermap.ConsumerMapRegistry;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class AuditConsumer {

    private static final Logger LOG = Logger.getLogger(AuditConsumer.class);

    @Inject
    AuditService service;

    @Inject
    ObjectMapper mapper;

    @Inject
    ConsumerMapRegistry registry;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    @Channel("audit-declarations-dlq")
    Emitter<String> declarationsDeadLetterEmitter;

    @Inject
    @Channel("audit-validations-dlq")
    Emitter<String> validationsDeadLetterEmitter;

    @Inject
    @Channel("audit-risk-dlq")
    Emitter<String> riskDeadLetterEmitter;

    @Incoming("audit-declarations")
    public void onDeclaration(List<Message<String>> batch) throws Exception {
        processBatch(batch, "audit-declarations", "declarations.created", declarationsDeadLetterEmitter);
    }

    @Incoming("audit-validations")
    public void onValidation(List<Message<String>> batch) throws Exception {
        processBatch(batch, "audit-validations", "validations.completed", validationsDeadLetterEmitter);
    }

    @Incoming("audit-risk")
    public void onRisk(List<Message<String>> batch) throws Exception {
        processBatch(batch, "audit-risk", "risk.assessed", riskDeadLetterEmitter);
    }

    private void processBatch(List<Message<String>> batch, String channel, String topic,
                               Emitter<String> deadLetterEmitter) throws Exception {
        if (!registry.isEnabled(channel)) return;

        int successCount = 0;
        int deadLetteredCount = 0;
        for (Message<String> message : batch) {
            String messageJson = message.getPayload();
            try {
                IncomingEvent event = mapper.readValue(messageJson, IncomingEvent.class);
                service.record(topic, event, messageJson);
                successCount++;
            } catch (Exception e) {
                deadLetterEmitter.send(mapper.writeValueAsString(new DeadLetterEnvelope(
                        messageJson, topic, e.getMessage(), Instant.now().toString(), "svc-audit")));
                deadLetteredCount++;
            }
            message.ack();
        }

        meterRegistry.summary("kafka.batch.size", "channel", channel).record(batch.size());
        meterRegistry.counter("kafka.batch.records.success", "channel", channel).increment(successCount);
        meterRegistry.counter("kafka.batch.records.deadlettered", "channel", channel).increment(deadLetteredCount);

        LOG.infof("batch.processed channel=%s batchSize=%d success=%d deadLettered=%d",
                channel, batch.size(), successCount, deadLetteredCount);
    }
}
