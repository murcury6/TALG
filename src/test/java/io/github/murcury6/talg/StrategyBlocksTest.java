package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StrategyBlocksTest {
    @Test void namedIndicatorsBecomeSelectableSignalsAndConditionsRemainInspectable() {
        String original = StrategyScript.starter();
        assertTrue(StrategyBlocks.signals(original).contains("trend"));
        assertTrue(StrategyBlocks.signals(original).contains("edge"));
        String combined = StrategyBlocks.addCondition(original, "Buy", "close", ">", "trend", "AND");
        assertTrue(combined.contains("buy (edge > 0.01 and pressure > 1.10) and (close > trend)"));
        assertTrue(StrategyScript.parse(combined).evaluate(Map.of(
                "close", 102.0, "trend", 100.0, "pressure", 1.2)).buy());
    }

    @Test void blockCanCreateMissingSideAndUseNumericThreshold() {
        String original = StrategyScript.starter().replace("sell edge < -0.01\n", "");
        String combined = StrategyBlocks.addCondition(original, "Sell", "pressure", "<", "0.8", "OR");
        assertTrue(combined.contains("sell pressure < 0.8\nqty 1"));
        assertTrue(StrategyScript.parse(combined).evaluate(Map.of(
                "close", 100.0, "trend", 100.0, "pressure", 0.7)).sell());
    }

    @Test void invalidBlockLeavesScriptUntouched() {
        String original = StrategyScript.starter();
        assertThrows(IllegalArgumentException.class,
                () -> StrategyBlocks.addCondition(original, "Buy", "forecast", ">", "1", "AND"));
        assertEquals(original, StrategyScript.starter());
    }

    @Test void equationFeedsAConditionAndEvaluatesFromObservedInputs() {
        String original = StrategyScript.starter();
        String withEquation = StrategyBlocks.addEquation(original, "distance_pct", "close",
                "% difference", "trend");
        assertTrue(withEquation.contains("let distance_pct = (close / trend - 1) * 100"));
        String combined = StrategyBlocks.addCondition(withEquation, "Buy", "distance_pct", ">", "2", "AND");
        assertTrue(StrategyScript.parse(combined).evaluate(Map.of(
                "close", 103.0, "trend", 100.0, "pressure", 1.2)).buy());
        assertThrows(IllegalArgumentException.class,
                () -> StrategyBlocks.addEquation(withEquation, "distance_pct", "close", "+", "1"));
    }
}
