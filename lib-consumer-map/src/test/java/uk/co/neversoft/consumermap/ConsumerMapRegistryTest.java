package uk.co.neversoft.consumermap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConsumerMapRegistryTest {

    @TempDir
    Path tempDir;

    private ConsumerMapRegistry registry;

    @BeforeEach
    void setUp() throws IOException {
        Path file = tempDir.resolve("consumer-map.yml");
        Files.writeString(file, """
            version: "1.0"
            hot-reload:
              enabled-environments:
                - dev
            events:
              declarations.created:
                consumers:
                  - service: svc-validate
                    channel: declarations-created
                    enabled: true
                  - service: svc-audit
                    channel: audit-declarations
                    enabled: false
              validations.completed:
                consumers:
                  - service: svc-risk
                    channel: validations-completed
            """);
        registry = new ConsumerMapRegistry();
        registry.doInit(file.toString());
    }

    @Test
    void isEnabledReturnsTrueForPresentAndEnabledChannel() {
        assertTrue(registry.isEnabled("declarations-created"));
    }

    @Test
    void isEnabledReturnsFalseForPresentButDisabledChannel() {
        assertFalse(registry.isEnabled("audit-declarations"));
    }

    @Test
    void isEnabledReturnsTrueWhenEnabledFieldAbsent() {
        // validations-completed consumer has no enabled field — defaults to true
        assertTrue(registry.isEnabled("validations-completed"));
    }

    @Test
    void isEnabledReturnsFalseForUnknownChannel() {
        assertFalse(registry.isEnabled("no-such-channel"));
    }

    @Test
    void activeSnapshotIsNeverNullAfterInit() {
        assertNotNull(registry.activeSnapshot());
        assertNotNull(registry.activeSnapshot().map());
        assertNotNull(registry.activeSnapshot().filePath());
        assertNotNull(registry.activeSnapshot().loadedAt());
    }

    @Test
    void updateSnapshotReplacesActiveSnapshot() throws Exception {
        Path newFile = tempDir.resolve("consumer-map-v2.yml");
        Files.writeString(newFile, """
            version: "1.0"
            hot-reload:
              enabled-environments: []
            events:
              declarations.created:
                consumers:
                  - service: svc-validate
                    channel: declarations-created
                    enabled: false
            """);

        ConsumerMapSnapshot newSnap = new ConsumerMapLoader().load(newFile.toString());
        registry.updateSnapshot(newSnap);

        assertFalse(registry.isEnabled("declarations-created"),
            "After swap, previously-enabled channel should now be disabled");
        assertSame(newSnap, registry.activeSnapshot());
    }
}
