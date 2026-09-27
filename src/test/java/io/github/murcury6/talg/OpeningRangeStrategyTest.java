package io.github.murcury6.talg;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpeningRangeStrategyTest {
    List<IntradayMomentum.Bar> bars() {
        var bars = new ArrayList<IntradayMomentum.Bar>();
        Instant start = Instant.parse("2026-09-23T13:30:00Z");
        for (int i = 0; i < 15; i++) bars.add(new IntradayMomentum.Bar(start.plusSeconds(60L * i).toString(), 700, 701, 699, 700, 1000, 700));
        return bars;
    }
    @Test void needsCompleteOpeningRangeAndFreshCrossAboveIt() {
        var bars = bars(); assertFalse(OpeningRangeStrategy.evaluate(bars).enter());
        bars.add(new IntradayMomentum.Bar("2026-09-23T13:45:00Z", 700.5, 701.5, 700.4, 701.4, 1000, 701));
        var signal = OpeningRangeStrategy.evaluate(bars);
        assertTrue(signal.enter()); assertEquals("2026-09-23T13:46:00Z", signal.asOf()); assertEquals(2.4, signal.risk(), .0001);
        bars.add(new IntradayMomentum.Bar("2026-09-23T13:46:00Z", 701.4, 702, 701.3, 701.8, 1000, 701.6));
        assertFalse(OpeningRangeStrategy.evaluate(bars).enter(), "Staying above the range is not a new cross");
        bars.remove(3); assertFalse(OpeningRangeStrategy.evaluate(bars).enter(), "Missing opening minute must invalidate the session");
    }
    @Test void rejectsLargeRiskAndLateSignals() {
        var bars = bars();
        bars.add(new IntradayMomentum.Bar("2026-09-23T13:45:00Z", 701, 710, 701, 709, 1000, 704));
        assertFalse(OpeningRangeStrategy.evaluate(bars).enter());
        bars.set(15, new IntradayMomentum.Bar("2026-09-23T15:00:00Z", 700.5, 701.5, 700.4, 701.4, 1000, 701));
        assertFalse(OpeningRangeStrategy.evaluate(bars).enter());
    }
}
