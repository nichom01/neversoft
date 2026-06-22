package uk.co.neversoft.risk.unit;

import org.junit.jupiter.api.Test;
import uk.co.neversoft.risk.service.StubRiskScorer;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StubRiskScorerTest {

    private final StubRiskScorer scorer = new StubRiskScorer();

    @Test
    void score_alwaysReturnsZero() {
        assertEquals(0.0, scorer.score(UUID.randomUUID()));
    }

    @Test
    void band_alwaysReturnsLow() {
        assertEquals("LOW", scorer.band(0.0));
        assertEquals("LOW", scorer.band(99.9));
    }
}
