package uk.co.neversoft.declare.component;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;

@QuarkusTest
class DeclarationResourceTest {

    @Test
    void createDeclaration_returnsCreated() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "customerId": "%s",
                          "idempotencyKey": "idem-create-001",
                          "payload": {"amount": 100.0, "currency": "GBP"}
                        }
                        """.formatted(UUID.randomUUID()))
                .when().post("/declarations")
                .then()
                .statusCode(201);
    }

    @Test
    void createDeclaration_duplicateKey_returnsOk() {
        String body = """
                {
                  "customerId": "%s",
                  "idempotencyKey": "idem-duplicate-001",
                  "payload": {}
                }
                """.formatted(UUID.randomUUID());

        given().contentType(ContentType.JSON).body(body)
                .when().post("/declarations")
                .then().statusCode(201);

        given().contentType(ContentType.JSON).body(body)
                .when().post("/declarations")
                .then().statusCode(200);
    }
}
