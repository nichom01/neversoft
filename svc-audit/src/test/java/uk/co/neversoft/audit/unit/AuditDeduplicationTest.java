package uk.co.neversoft.audit.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uk.co.neversoft.audit.messaging.IncomingEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AuditDeduplicationTest {

    @Test
    void rawPayloadRoundtrips() throws Exception {
        String json = "{\"eventId\":\"abc\",\"eventType\":\"declarations.created\",\"aggregateId\":\"xyz\",\"extra\":\"ignored\"}";
        IncomingEvent event = new ObjectMapper().readValue(json, IncomingEvent.class);

        assertEquals("abc",                    event.eventId());
        assertEquals("declarations.created",   event.eventType());
        assertEquals("xyz",                    event.aggregateId());
    }

    @Test
    void unknownFieldsAreIgnored() throws Exception {
        String json = "{\"eventId\":\"1\",\"eventType\":\"risk.assessed\",\"aggregateId\":\"2\",\"score\":0.0,\"band\":\"LOW\"}";
        IncomingEvent event = new ObjectMapper().readValue(json, IncomingEvent.class);

        assertNotNull(event);
        assertEquals("1", event.eventId());
    }
}
