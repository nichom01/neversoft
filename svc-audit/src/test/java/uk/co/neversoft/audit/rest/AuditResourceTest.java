package uk.co.neversoft.audit.rest;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import uk.co.neversoft.audit.messaging.IncomingEvent;
import uk.co.neversoft.audit.service.AuditService;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class AuditResourceTest {

    @Inject
    AuditService service;

    @Test
    void list_filtersByTopicAndAggregateId() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String aggregateId = UUID.randomUUID().toString();
        String raw = payload(eventId, "declarations.created", aggregateId);
        service.record("declarations.created", new IncomingEvent(eventId, "declarations.created", aggregateId), raw);

        given()
            .queryParam("topic", "declarations.created")
            .queryParam("aggregateId", aggregateId)
        .when()
            .get("/audit")
        .then()
            .statusCode(200)
            .body("$", hasSize(1))
            .body("[0].eventId", equalTo(eventId))
            .body("[0].topic", equalTo("declarations.created"))
            .body("[0].rawPayload.eventId", equalTo(eventId));
    }

    @Test
    void list_paginates() throws Exception {
        String aggregateId = UUID.randomUUID().toString();
        for (int i = 0; i < 3; i++) {
            String eventId = UUID.randomUUID().toString();
            service.record("risk.assessed",
                new IncomingEvent(eventId, "risk.assessed", aggregateId),
                payload(eventId, "risk.assessed", aggregateId));
        }

        given()
            .queryParam("aggregateId", aggregateId)
            .queryParam("page", 0)
            .queryParam("size", 2)
        .when()
            .get("/audit")
        .then()
            .statusCode(200)
            .body("$", hasSize(2));
    }

    @Test
    void getById_returnsEntry() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String aggregateId = UUID.randomUUID().toString();
        service.record("risk.assessed",
            new IncomingEvent(eventId, "risk.assessed", aggregateId),
            payload(eventId, "risk.assessed", aggregateId));

        String id = given()
            .queryParam("topic", "risk.assessed")
            .queryParam("aggregateId", aggregateId)
        .when()
            .get("/audit")
        .then()
            .statusCode(200)
            .extract().path("[0].id");

        given()
        .when()
            .get("/audit/" + id)
        .then()
            .statusCode(200)
            .body("eventId", equalTo(eventId));
    }

    @Test
    void getById_notFound_returns404() {
        given()
        .when()
            .get("/audit/" + UUID.randomUUID())
        .then()
            .statusCode(404);
    }

    private static String payload(String eventId, String eventType, String aggregateId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + eventType + "\",\"aggregateId\":\"" + aggregateId + "\"}";
    }
}
