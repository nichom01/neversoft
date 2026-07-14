package uk.co.neversoft.validate.component;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;
import uk.co.neversoft.validate.domain.Validation;
import uk.co.neversoft.validate.messaging.DeclarationConsumer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DeclarationConsumerMetricsTest {

    static final UUID KNOWN_CUSTOMER = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

    @Inject
    DeclarationConsumer consumer;

    @Inject
    MeterRegistry meterRegistry;

    @Test
    void batchMetricsAreRecorded() throws Exception {
        double successCountBefore = meterRegistry.counter("kafka.batch.records.success", "channel", "declarations-created").count();

        String eventId = UUID.randomUUID().toString();
        String declarationId = UUID.randomUUID().toString();
        List<Message<String>> batch = List.of(Message.of("""
            {"eventId":"%s","eventType":"declarations.created","aggregateId":"%s",
             "occurredAt":"%s","payload":{"declarationId":"%s","customerId":"%s"}}
            """.formatted(eventId, declarationId, Instant.now(), declarationId, KNOWN_CUSTOMER)));

        consumer.consume(batch);

        assertNotNull(Validation.find("eventId", eventId).firstResult());
        assertTrue(meterRegistry.counter("kafka.batch.records.success", "channel", "declarations-created").count()
                > successCountBefore);
        assertTrue(meterRegistry.summary("kafka.batch.size", "channel", "declarations-created").count() > 0);
    }
}
