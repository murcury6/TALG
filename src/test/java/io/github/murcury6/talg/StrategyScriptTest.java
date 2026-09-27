package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StrategyScriptTest {
    @org.junit.jupiter.api.Test void declaresObservedInputsParametersAndNamedOutputs() {
        String source = "name Custom\nticker AAPL\nrange 1D\nbars 1Min\n"
                + "input forecast Observed(predicted_return)\nparam threshold = 0.01\n"
                + "output conviction = clamp(forecast / threshold, -1, 1)\nbuy conviction > 0.5\nsell conviction < 0\nqty 1\n";
        var parsed = StrategyScript.parse(source, java.util.Set.of("predicted_return"));
        var result = parsed.evaluate(java.util.Map.of("predicted_return", 0.02));
        org.junit.jupiter.api.Assertions.assertEquals(1.0, result.values().get("conviction"));
        org.junit.jupiter.api.Assertions.assertTrue(result.buy());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> StrategyScript.parse(source.replace("Observed(predicted_return)", "Observed(unavailable)"), java.util.Set.of("predicted_return")));
    }

    @Test void evaluatesBooleanRulesAndEquationsFromObservedInputs() {
        StrategyScript.Parsed rule = StrategyScript.parse(StrategyScript.starter());
        assertEquals("AAPL", rule.ticker());
        assertEquals(2, rule.inputs().size());
        StrategyScript.Evaluation result = rule.evaluate(Map.of("close", 105.0,
                "trend", 100.0, "pressure", 1.25));
        assertTrue(result.buy());
        assertFalse(result.sell());
        assertEquals(0.05, result.values().get("edge"), 1e-9);
    }

    @Test void supportsModelInputAndRejectsUnsafeOrConflictingRules() {
        String source = StrategyScript.starter().replace("input pressure Indicator(volume_ratio_20)",
                "input pressure Model(alpha_model,expected_return)");
        assertEquals("model", StrategyScript.parse(source).inputs().get(1).kind());
        assertEquals("custom", StrategyScript.parse(StrategyScript.starter()
                .replace("Indicator(ema_34)", "Custom(ema_34)"))
                .inputs().getFirst().kind());
        assertThrows(IllegalArgumentException.class, () -> StrategyScript.parse(
                StrategyScript.starter().replace("let edge = close / trend - 1", "let edge = system()")));
        assertThrows(IllegalArgumentException.class, () -> StrategyScript.parse(
                StrategyScript.starter().replace("qty 1", "qty 1000")));
        StrategyScript.Parsed conflict = StrategyScript.parse(StrategyScript.starter()
                .replace("sell edge < -0.01", "sell edge > 0.01"));
        assertThrows(IllegalArgumentException.class, () -> conflict.evaluate(Map.of(
                "close", 105.0, "trend", 100.0, "pressure", 1.25)));
    }
}
