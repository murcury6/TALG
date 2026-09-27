package io.github.murcury6.talg;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StatisticalReturnModelTest {
    @Test void learnsStatisticalRelationshipAndScoresLaterExamples() {
        Random random = new Random(42); List<StatisticalReturnModel.Example> data = new ArrayList<>();
        for (int i = 0; i < 1200; i++) { double[] x = new double[7]; for (int j = 0; j < 7; j++) x[j] = random.nextGaussian();
            data.add(new StatisticalReturnModel.Example(i < 900 ? "2026-09-17" : "2026-09-18", "AAPL", x, .002 * x[0] - .001 * x[1], 100)); }
        var model = StatisticalReturnModel.fit(data.subList(0, 900), List.of("AAPL"), "2026-09-17").validate(data.subList(900, 1200));
        assertTrue(model.weights()[1] > 0); assertTrue(model.weights()[2] < 0);
        assertTrue(model.validation().get("mseImprovementVsZero") > .9);
        assertTrue(model.validation().get("directionAccuracy") > .95);
    }
    @Test void changedRuleBrickChangesDecisionWithoutChangingPredictor() {
        var model = RapidPaperModel.defaults(); var bars = new ArrayList<IntradayMomentum.Bar>();
        for (int i = 0; i < 8; i++) bars.add(RapidPaperRunnerTest.bar(RapidPaperRunnerTest.START.minusSeconds((8 - i) * 60)));
        assertTrue(model.evaluate(bars, -.001, .0005, 1).buy(), "Volume mode ranks even forecasts that do not cover costs; it must be labeled clearly");
        var costModel = new RapidPaperModel(model.version(), model.universe(), model.prediction(), new RapidPaperModel.Signal(model.signal().script().replace("buy prediction_rank >= 0.75", "buy expected_edge > 0"), 8), model.sizing(), model.exits(), model.portfolio(), model.execution());
        assertFalse(costModel.evaluate(bars, -.001, .0005, 1).buy()); assertTrue(costModel.evaluate(bars, .001, .0005, 1).buy());
    }
}
