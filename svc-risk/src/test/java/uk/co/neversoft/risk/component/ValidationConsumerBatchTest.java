package uk.co.neversoft.risk.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;
import uk.co.neversoft.risk.domain.RiskAssessment;
import uk.co.neversoft.risk.messaging.DeadLetterEnvelope;
import uk.co.neversoft.risk.messaging.ValidationConsumer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Note: {@code mp.messaging.incoming.*.batch=true} is a Kafka-connector-specific feature —
 * the in-memory connector used elsewhere in this suite always delivers single payloads, so
 * batch delivery cannot be exercised end-to-end via {@code @Incoming} in tests. These tests
 * invoke {@link ValidationConsumer#consume(List)} directly with a hand-built batch instead.
 */
@QuarkusTest
class ValidationConsumerBatchTest {

    @Inject
    ValidationConsumer consumer;

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    ObjectMapper mapper;

    @Test
    void batchOfValidMessages_allProcessedSuccessfully() throws Exception {
        InMemorySink<String> dlq = connector.sink("validations-completed-dlq");
        dlq.clear();

        List<String> eventIds = IntStream.range(0, 20)
                .mapToObj(i -> UUID.randomUUID().toString())
                .toList();

        List<Message<String>> batch = eventIds.stream()
                .map(eventId -> Message.of(payload(eventId)))
                .toList();

        consumer.consume(batch);

        for (String eventId : eventIds) {
            assertNotNull(RiskAssessment.find("eventId", eventId).firstResult(),
                    "expected risk assessment for eventId " + eventId);
        }
        assertTrue(dlq.received().isEmpty(), "no messages should be dead-lettered for valid input");
    }

    @Test
    void batchWithOneMalformedMessage_isolatesFailureAndDeadLetters() throws Exception {
        InMemorySink<String> dlq = connector.sink("validations-completed-dlq");
        dlq.clear();

        List<String> validEventIds = IntStream.range(0, 5)
                .mapToObj(i -> UUID.randomUUID().toString())
                .toList();

        String malformed = "{not-valid-json";

        List<Message<String>> batch = new ArrayList<>();
        validEventIds.forEach(eventId -> batch.add(Message.of(payload(eventId))));
        batch.add(Message.of(malformed));

        consumer.consume(batch);

        for (String eventId : validEventIds) {
            assertNotNull(RiskAssessment.find("eventId", eventId).firstResult());
        }
        assertEquals(1, dlq.received().size());

        DeadLetterEnvelope envelope = mapper.readValue(dlq.received().get(0).getPayload(), DeadLetterEnvelope.class);
        assertEquals(malformed, envelope.originalPayload());
        assertEquals("validations.completed", envelope.originalTopic());
        assertEquals("svc-risk", envelope.serviceName());
        assertNotNull(envelope.failureReason());
    }

    private static String payload(String eventId) {
        return """
            {"eventId":"%s","eventType":"validations.completed","aggregateId":"%s",
             "declarationId":"%s","outcome":"PASSED","failureReason":null,"occurredAt":"%s"}
            """.formatted(eventId, UUID.randomUUID(), UUID.randomUUID(), Instant.now());
    }
}
