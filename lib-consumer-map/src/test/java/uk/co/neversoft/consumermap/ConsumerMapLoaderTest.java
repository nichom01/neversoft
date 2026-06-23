package uk.co.neversoft.consumermap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConsumerMapLoaderTest {

    private final ConsumerMapLoader loader = new ConsumerMapLoader();

    @TempDir
    Path tempDir;

    // ── valid baseline ──────────────────────────────────────────────────────────

    @Test
    void loadsValidBaselineWith5Registrations() throws Exception {
        Path file = writeYaml("""
            version: "1.0"
            hot-reload:
              enabled-environments:
                - local
                - dev
                - staging
              poll-interval-seconds: 30
            events:
              declarations.created:
                consumers:
                  - service: svc-validate
                    channel: declarations-created
                    enabled: true
                  - service: svc-audit
                    channel: audit-declarations
                    enabled: true
              validations.completed:
                consumers:
                  - service: svc-risk
                    channel: validations-completed
                    enabled: true
                  - service: svc-audit
                    channel: audit-validations
                    enabled: true
              risk.assessed:
                consumers:
                  - service: svc-audit
                    channel: audit-risk
                    enabled: true
            """);

        ConsumerMapSnapshot snap = loader.load(file.toString());

        assertEquals("1.0", snap.map().version());
        int totalConsumers = snap.map().events().values().stream()
            .mapToInt(e -> e.consumers().size())
            .sum();
        assertEquals(5, totalConsumers);
        assertNotNull(snap.loadedAt());
        assertEquals(file.toAbsolutePath(), snap.filePath());
    }

    @Test
    void enabledDefaultsTrueWhenAbsent() throws Exception {
        Path file = writeYaml("""
            version: "1.0"
            hot-reload:
              enabled-environments: []
            events:
              declarations.created:
                consumers:
                  - service: svc-validate
                    channel: declarations-created
            """);

        ConsumerMapSnapshot snap = loader.load(file.toString());
        ConsumerRegistration reg = snap.map().events().get("declarations.created").consumers().getFirst();
        assertTrue(reg.isEffectivelyEnabled());
    }

    // ── validation failure cases ────────────────────────────────────────────────

    @Test
    void rejectsUnknownVersion() {
        assertValidationFails("""
            version: "2.0"
            hot-reload:
              enabled-environments: []
            events:
              declarations.created:
                consumers:
                  - service: svc-validate
                    channel: declarations-created
            """, "version");
    }

    @Test
    void rejectsMissingVersion() {
        assertValidationFails("""
            hot-reload:
              enabled-environments: []
            events:
              declarations.created:
                consumers:
                  - service: svc-validate
                    channel: declarations-created
            """, "version");
    }

    @Test
    void rejectsEmptyEventsMap() {
        assertValidationFails("""
            version: "1.0"
            hot-reload:
              enabled-environments: []
            events: {}
            """, "events");
    }

    @Test
    void rejectsEmptyConsumersList() {
        assertValidationFails("""
            version: "1.0"
            hot-reload:
              enabled-environments: []
            events:
              declarations.created:
                consumers: []
            """, "consumers");
    }

    @Test
    void rejectsDuplicateChannelWithinEvent() {
        assertValidationFails("""
            version: "1.0"
            hot-reload:
              enabled-environments: []
            events:
              declarations.created:
                consumers:
                  - service: svc-validate
                    channel: declarations-created
                  - service: svc-audit
                    channel: declarations-created
            """, "duplicate");
    }

    @Test
    void rejectsChannelWithUppercase() {
        assertValidationFails("""
            version: "1.0"
            hot-reload:
              enabled-environments: []
            events:
              declarations.created:
                consumers:
                  - service: svc-validate
                    channel: Declarations-Created
            """, "channel");
    }

    @Test
    void rejectsChannelStartingWithDigit() {
        assertValidationFails("""
            version: "1.0"
            hot-reload:
              enabled-environments: []
            events:
              declarations.created:
                consumers:
                  - service: svc-validate
                    channel: 1declarations
            """, "channel");
    }

    @Test
    void rejectsPollIntervalBelowMinimum() {
        assertValidationFails("""
            version: "1.0"
            hot-reload:
              enabled-environments: []
              poll-interval-seconds: 4
            events:
              declarations.created:
                consumers:
                  - service: svc-validate
                    channel: declarations-created
            """, "poll-interval-seconds");
    }

    @Test
    void rejectsMissingFile() {
        ConsumerMapValidationException ex = assertThrows(ConsumerMapValidationException.class,
            () -> loader.load("/tmp/does-not-exist-consumer-map.yml"));
        assertTrue(ex.getMessage().contains("not found"), ex.getMessage());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private Path writeYaml(String content) throws IOException {
        Path file = tempDir.resolve("consumer-map.yml");
        Files.writeString(file, content);
        return file;
    }

    private void assertValidationFails(String yaml, String expectedMessageFragment) {
        Path file;
        try {
            file = writeYaml(yaml);
        } catch (IOException e) {
            fail("Could not write temp file: " + e.getMessage());
            return;
        }
        ConsumerMapValidationException ex = assertThrows(ConsumerMapValidationException.class,
            () -> loader.load(file.toString()),
            "Expected validation to fail with message containing: " + expectedMessageFragment);
        assertTrue(ex.getMessage().toLowerCase().contains(expectedMessageFragment.toLowerCase()),
            "Expected message to contain '" + expectedMessageFragment + "' but got: " + ex.getMessage());
    }
}
