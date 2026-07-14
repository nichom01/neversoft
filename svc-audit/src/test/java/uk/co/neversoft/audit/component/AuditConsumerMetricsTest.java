package uk.co.neversoft.audit.component;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;
import uk.co.neversoft.audit.domain.AuditEntry;
import uk.co.neversoft.audit.messaging.AuditConsumer;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AuditConsumerMetricsTest {

    @Inject
    AuditConsumer consumer;

    @Inject
    MeterRegistry meterRegistry;

    @Test
    void batchMetricsAreRecordedPerChannel() throws Exception {
        double successCountBefore = meterRegistry.counter("kafka.batch.records.success", "channel", "audit-declarations").count();

        String eventId = UUID.randomUUID().toString();
        List<Message<String>> batch = List.of(Message.of("""
            {"eventId":"%s","eventType":"declarations.created","aggregateId":"%s"}
            """.formatted(eventId, UUID.randomUUID())));

        consumer.onDeclaration(batch);

        assertNotNull(AuditEntry.find("eventId", eventId).firstResult());
        assertTrue(meterRegistry.counter("kafka.batch.records.success", "channel", "audit-declarations").count()
                > successCountBefore);
        assertTrue(meterRegistry.summary("kafka.batch.size", "channel", "audit-declarations").count() > 0);
    }
}
