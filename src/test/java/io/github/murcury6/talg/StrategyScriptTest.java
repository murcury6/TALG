package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StrategyScriptTest {
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
        String source = StrategyScript.starter().replace("input pressure Custom(volume_ratio_20)",
                "input pressure Model(alpha_model,expected_return)");
        assertEquals("model", StrategyScript.parse(source).inputs().get(1).kind());
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
