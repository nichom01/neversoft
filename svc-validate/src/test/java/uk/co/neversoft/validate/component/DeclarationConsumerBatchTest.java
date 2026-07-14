package uk.co.neversoft.validate.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;
import uk.co.neversoft.validate.domain.Validation;
import uk.co.neversoft.validate.messaging.DeadLetterEnvelope;
import uk.co.neversoft.validate.messaging.DeclarationConsumer;

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
 * invoke {@link DeclarationConsumer#consume(List)} directly with a hand-built batch instead,
 * which exercises the same per-record processing/DLQ/ack logic the real Kafka connector would drive.
 */
@QuarkusTest
class DeclarationConsumerBatchTest {

    static final UUID KNOWN_CUSTOMER = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

    @Inject
    DeclarationConsumer consumer;

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    ObjectMapper mapper;

    @Test
    void batchOfValidMessages_allProcessedSuccessfully() throws Exception {
        InMemorySink<String> dlq = connector.sink("declarations-created-dlq");
        dlq.clear();

        List<String> eventIds = IntStream.range(0, 20)
                .mapToObj(i -> UUID.randomUUID().toString())
                .toList();

        List<Message<String>> batch = eventIds.stream()
                .map(eventId -> Message.of(payload(eventId, UUID.randomUUID().toString())))
                .toList();

        consumer.consume(batch);

        for (String eventId : eventIds) {
            assertNotNull(Validation.find("eventId", eventId).firstResult(),
                    "expected validation for eventId " + eventId);
        }
        assertTrue(dlq.received().isEmpty(), "no messages should be dead-lettered for valid input");
    }

    @Test
    void batchWithOneMalformedMessage_isolatesFailureAndDeadLetters() throws Exception {
        InMemorySink<String> dlq = connector.sink("declarations-created-dlq");
        dlq.clear();

        List<String> validEventIds = IntStream.range(0, 5)
                .mapToObj(i -> UUID.randomUUID().toString())
                .toList();

        String malformed = "{not-valid-json";

        List<Message<String>> batch = new ArrayList<>();
        validEventIds.forEach(eventId -> batch.add(Message.of(payload(eventId, UUID.randomUUID().toString()))));
        batch.add(Message.of(malformed));

        consumer.consume(batch);

        for (String eventId : validEventIds) {
            assertNotNull(Validation.find("eventId", eventId).firstResult());
        }
        assertEquals(1, dlq.received().size());

        DeadLetterEnvelope envelope = mapper.readValue(dlq.received().get(0).getPayload(), DeadLetterEnvelope.class);
        assertEquals(malformed, envelope.originalPayload());
        assertEquals("declarations.created", envelope.originalTopic());
        assertEquals("svc-validate", envelope.serviceName());
        assertNotNull(envelope.failureReason());
    }

    private static String payload(String eventId, String declarationId) {
        return """
            {"eventId":"%s","eventType":"declarations.created","aggregateId":"%s",
             "occurredAt":"%s","payload":{"declarationId":"%s","customerId":"%s"}}
            """.formatted(eventId, declarationId, Instant.now(), declarationId, KNOWN_CUSTOMER);
    }
}
