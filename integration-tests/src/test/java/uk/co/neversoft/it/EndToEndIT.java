package uk.co.neversoft.it;

import io.restassured.RestAssured;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;

import java.sql.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests against the full Docker Compose stack.
 *
 * Prerequisites:
 *   docker compose -f infra/docker-compose.yml up --wait -d
 *
 * Run with: mvn verify -Pit -pl it-tests
 */
class EndToEndIT {

    // Known customer seeded by validate-svc V1__create_customers.sql
    static final String KNOWN_CUSTOMER   = "550e8400-e29b-41d4-a716-446655440001";
    static final String UNKNOWN_CUSTOMER = "00000000-0000-0000-0000-000000000099";

    static Connection declareDb;
    static Connection riskDb;
    static Connection auditDb;

    @BeforeAll
    static void setup() throws Exception {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port    = 8080;
        Awaitility.setDefaultTimeout(30, TimeUnit.SECONDS);
        Awaitility.setDefaultPollInterval(500, TimeUnit.MILLISECONDS);

        declareDb = DriverManager.getConnection("jdbc:postgresql://localhost:5432/declare", "declare", "declare");
        riskDb    = DriverManager.getConnection("jdbc:postgresql://localhost:5434/risk",    "risk",    "risk");
        auditDb   = DriverManager.getConnection("jdbc:postgresql://localhost:5435/audit",   "audit",   "audit");
    }

    @AfterAll
    static void teardown() throws Exception {
        if (declareDb != null) declareDb.close();
        if (riskDb    != null) riskDb.close();
        if (auditDb   != null) auditDb.close();
    }

    // ── Test 1: happy path ────────────────────────────────────────────────────

    @Test
    void happyPath_allThreeEventsAudited_riskBandIsLow() throws Exception {
        UUID declarationId = postDeclaration(KNOWN_CUSTOMER, UUID.randomUUID().toString(), 201);

        await().until(() -> auditCountForDeclaration(declarationId) == 3);

        assertEquals("LOW", riskBandForDeclaration(declarationId));
    }

    // ── Test 2: validation failure ────────────────────────────────────────────

    @Test
    void validationFailure_twoAuditEntriesNoRisk() throws Exception {
        UUID declarationId = postDeclaration(UNKNOWN_CUSTOMER, UUID.randomUUID().toString(), 201);

        await().until(() -> auditCountForDeclaration(declarationId) >= 2);

        // Hold for 2 s to confirm no risk event arrives after the validation failure
        Thread.sleep(2_000);

        assertEquals(2L, auditCountForDeclaration(declarationId));
        assertEquals(0L, riskCountForDeclaration(declarationId));
    }

    // ── Test 3: duplicate declaration ────────────────────────────────────────

    @Test
    void duplicateDeclaration_idempotentThroughPipeline() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        UUID id1 = postDeclaration(KNOWN_CUSTOMER, idempotencyKey, 201);

        // Let the first declaration flow all the way through
        await().until(() -> auditCountForDeclaration(id1) == 3);

        // POST the same declaration a second time
        UUID id2 = postDeclaration(KNOWN_CUSTOMER, idempotencyKey, 200);
        assertEquals(id1, id2);

        // Wait briefly and verify the pipeline did not re-process
        Thread.sleep(2_000);

        assertEquals(1L, declarationCountByKey(idempotencyKey));
        assertEquals(3L, auditCountForDeclaration(id1));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID postDeclaration(String customerId, String idempotencyKey, int expectedStatus) {
        String raw = given()
                .contentType("application/json")
                .body(Map.of(
                        "customerId",     customerId,
                        "idempotencyKey", idempotencyKey,
                        "payload",        Map.of()
                ))
                .when().post("/declarations")
                .then().statusCode(expectedStatus)
                .extract().body().asString();
        return UUID.fromString(raw.replace("\"", "").trim());
    }

    /**
     * Counts audit_log rows that belong to a given declaration.
     *
     * declarations.created  → declarationId nested at payload.declarationId
     * validations.completed → declarationId at top level
     * risk.assessed         → declarationId at top level
     */
    private long auditCountForDeclaration(UUID declarationId) throws Exception {
        String sql = """
                SELECT COUNT(*) FROM audit_log
                WHERE raw_payload::jsonb->>'declarationId' = ?
                   OR raw_payload::jsonb->'payload'->>'declarationId' = ?
                """;
        try (PreparedStatement ps = auditDb.prepareStatement(sql)) {
            ps.setString(1, declarationId.toString());
            ps.setString(2, declarationId.toString());
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    private String riskBandForDeclaration(UUID declarationId) throws Exception {
        String sql = "SELECT band FROM risk_assessments WHERE declaration_id = ?";
        try (PreparedStatement ps = riskDb.prepareStatement(sql)) {
            ps.setObject(1, declarationId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("band") : null;
        }
    }

    private long riskCountForDeclaration(UUID declarationId) throws Exception {
        String sql = "SELECT COUNT(*) FROM risk_assessments WHERE declaration_id = ?";
        try (PreparedStatement ps = riskDb.prepareStatement(sql)) {
            ps.setObject(1, declarationId);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    private long declarationCountByKey(String idempotencyKey) throws Exception {
        String sql = "SELECT COUNT(*) FROM declarations WHERE idempotency_key = ?";
        try (PreparedStatement ps = declareDb.prepareStatement(sql)) {
            ps.setString(1, idempotencyKey);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }
}
