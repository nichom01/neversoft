package uk.co.neversoft.it;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the ConsumerMapWatcher does NOT start in the prod profile.
 *
 * Prerequisites:
 *   docker compose -f infra/docker-compose.yml up --wait -d
 *
 * Run with: mvn verify -Pit -pl integration-tests
 */
class ConsumerMapEnvGatingIT {

    private static final String SERVICE = "svc-audit";
    private static final int LOG_CHECK_TIMEOUT_SECONDS = 10;

    @Test
    void watcherIsDisabledInProdProfile() throws Exception {
        String logs = dockerComposeLogs(SERVICE);
        assertTrue(
            logs.contains("ConsumerMapWatcher disabled"),
            "Expected 'ConsumerMapWatcher disabled' in " + SERVICE + " logs but got:\n" + tailLines(logs, 30)
        );
    }

    @Test
    void noReloadLogAfterSixtySeconds() throws Exception {
        // Capture log length before waiting
        String logsBefore = dockerComposeLogs(SERVICE);
        long reloadCountBefore = countOccurrences(logsBefore, "ConsumerMapWatcher: reload detected");

        // Wait 65 seconds — longer than two poll intervals
        Thread.sleep(65_000);

        String logsAfter = dockerComposeLogs(SERVICE);
        long reloadCountAfter = countOccurrences(logsAfter, "ConsumerMapWatcher: reload detected");

        assertEquals(reloadCountBefore, reloadCountAfter,
            "Reload log must not appear in prod profile even after 65 seconds. "
                + "New reload logs found:\n" + tailLines(logsAfter, 20));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private String dockerComposeLogs(String service) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "docker", "compose",
            "-f", "../infra/docker-compose.yml",
            "logs", "--no-color", service
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        boolean finished = process.waitFor(LOG_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        return output;
    }

    private long countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private String tailLines(String text, int n) {
        String[] lines = text.split("\n");
        int start = Math.max(0, lines.length - n);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }
}
