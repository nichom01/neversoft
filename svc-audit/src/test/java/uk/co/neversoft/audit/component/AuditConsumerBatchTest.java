package uk.co.neversoft.audit.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;
import uk.co.neversoft.audit.domain.AuditEntry;
import uk.co.neversoft.audit.messaging.AuditConsumer;
import uk.co.neversoft.audit.messaging.DeadLetterEnvelope;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Note: {@code mp.messaging.incoming.*.batch=true} is a Kafka-connector-specific feature —
 * the in-memory connector used elsewhere in this suite always delivers single payloads, so
 * batch delivery cannot be exercised end-to-end via {@code @Incoming} in tests. These tests
 * invoke {@link AuditConsumer}'s per-channel methods directly with a hand-built batch instead.
 */
@QuarkusTest
class AuditConsumerBatchTest {

    @Inject
    AuditConsumer consumer;

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    ObjectMapper mapper;

    @Test
    void batchOfValidMessages_allProcessedSuccessfullyOnEachChannel() throws Exception {
        assertChannelProcessesBatch("declarations.created", "audit-declarations-dlq", consumer::onDeclaration);
        assertChannelProcessesBatch("validations.completed", "audit-validations-dlq", consumer::onValidation);
        assertChannelProcessesBatch("risk.assessed", "audit-risk-dlq", consumer::onRisk);
    }

    private interface BatchHandler {
        void handle(List<Message<String>> batch) throws Exception;
    }

    private void assertChannelProcessesBatch(String eventType, String dlqChannel, BatchHandler handler) throws Exception {
        InMemorySink<String> dlq = connector.sink(dlqChannel);
        dlq.clear();

        List<String> eventIds = IntStream.range(0, 20)
                .mapToObj(i -> UUID.randomUUID().toString())
                .toList();

        List<Message<String>> batch = eventIds.stream()
                .map(eventId -> Message.of(payload(eventId, eventType)))
                .toList();

        handler.handle(batch);

        for (String eventId : eventIds) {
            assertNotNull(AuditEntry.find("eventId", eventId).firstResult(),
                    "expected audit entry for eventId " + eventId);
        }
        assertTrue(dlq.received().isEmpty(), "no messages should be dead-lettered for valid input");
    }

    @Test
    void batchWithOneMalformedMessage_isolatesFailureAndDeadLetters() throws Exception {
        InMemorySink<String> dlq = connector.sink("audit-declarations-dlq");
        dlq.clear();

        List<String> validEventIds = IntStream.range(0, 5)
                .mapToObj(i -> UUID.randomUUID().toString())
                .toList();

        String malformed = "{not-valid-json";

        List<Message<String>> batch = new ArrayList<>();
        validEventIds.forEach(eventId -> batch.add(Message.of(payload(eventId, "declarations.created"))));
        batch.add(Message.of(malformed));

        consumer.onDeclaration(batch);

        for (String eventId : validEventIds) {
            assertNotNull(AuditEntry.find("eventId", eventId).firstResult());
        }
        assertEquals(1, dlq.received().size());

        DeadLetterEnvelope envelope = mapper.readValue(dlq.received().get(0).getPayload(), DeadLetterEnvelope.class);
        assertEquals(malformed, envelope.originalPayload());
        assertEquals("declarations.created", envelope.originalTopic());
        assertEquals("svc-audit", envelope.serviceName());
        assertNotNull(envelope.failureReason());
    }

    private static String payload(String eventId, String eventType) {
        return """
            {"eventId":"%s","eventType":"%s","aggregateId":"%s"}
            """.formatted(eventId, eventType, UUID.randomUUID());
    }
}
