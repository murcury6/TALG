package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RiskEngineTest {
    private final RiskEngine engine = new RiskEngine();
    private final Instant now = Instant.parse("2026-09-22T20:00:00Z");

    private Signal signal(Instant asOf, double edge, double volatility) {
        return new Signal("AAPL", asOf, 100.0, edge, volatility, 1, "user-model-v1");
    }

    @Test void acceptsRecentForecastWithinPositionCap() {
        Decision decision = engine.evaluate(signal(now.minusSeconds(3600), 0.003, 0.25), now, 100_000);
        assertEquals("RESEARCH_CANDIDATE", decision.status());
        assertEquals(40, decision.shares());
        assertEquals(4_000.0, decision.maxNotionalUsd());
    }

    @Test void rejectsFutureAndStaleInformation() {
        assertEquals("FUTURE_SIGNAL", engine.evaluate(signal(now.plusSeconds(1), 0.003, 0.25), now, 100_000).reason());
        assertEquals("STALE_SIGNAL", engine.evaluate(signal(now.minusSeconds(73 * 3600), 0.003, 0.25), now, 100_000).reason());
    }

    @Test void rejectsWeakEdgeAndInvalidForecast() {
        assertEquals("INSUFFICIENT_EDGE", engine.evaluate(signal(now, 0.001, 0.25), now, 100_000).reason());
        assertThrows(IllegalArgumentException.class, () -> signal(now, Double.NaN, 0.25));
        assertThrows(IllegalArgumentException.class, () -> signal(now, 0.003, 0.0));
    }
}
