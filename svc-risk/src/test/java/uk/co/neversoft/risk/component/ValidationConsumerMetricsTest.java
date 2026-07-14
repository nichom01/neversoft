package uk.co.neversoft.risk.component;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;
import uk.co.neversoft.risk.domain.RiskAssessment;
import uk.co.neversoft.risk.messaging.ValidationConsumer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ValidationConsumerMetricsTest {

    @Inject
    ValidationConsumer consumer;

    @Inject
    MeterRegistry meterRegistry;

    @Test
    void batchMetricsAreRecorded() throws Exception {
        double successCountBefore = meterRegistry.counter("kafka.batch.records.success", "channel", "validations-completed").count();

        String eventId = UUID.randomUUID().toString();
        List<Message<String>> batch = List.of(Message.of("""
            {"eventId":"%s","eventType":"validations.completed","aggregateId":"%s",
             "declarationId":"%s","outcome":"PASSED","failureReason":null,"occurredAt":"%s"}
            """.formatted(eventId, UUID.randomUUID(), UUID.randomUUID(), Instant.now())));

        consumer.consume(batch);

        assertNotNull(RiskAssessment.find("eventId", eventId).firstResult());
        assertTrue(meterRegistry.counter("kafka.batch.records.success", "channel", "validations-completed").count()
                > successCountBefore);
        assertTrue(meterRegistry.summary("kafka.batch.size", "channel", "validations-completed").count() > 0);
    }
}
