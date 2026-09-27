package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndicatorWorkflowTest {
    @TempDir Path folder;

    @Test void namesAreUserChosenAndSavedSourcesCanBeListedAndRead() throws Exception {
        IndicatorRepository repository = new IndicatorRepository(folder);
        assertTrue(repository.names().isEmpty());
        assertEquals("close_change_10", IndicatorRepository.name(" Close_Change_10 "));
        assertThrows(IllegalArgumentException.class, () -> IndicatorRepository.name("../escape"));
        Files.createDirectories(repository.path("close_change_10").getParent());
        Files.writeString(repository.path("close_change_10"), "talg_indicator <- function(bars) bars$close");
        assertEquals(java.util.List.of("close_change_10"), repository.names());
        assertTrue(repository.read("close_change_10").contains("bars$close"));
    }

    @Test void oneSavedIndicatorCanBeInsertedIntoATradingRuleWithoutSubmitting() {
        TradingPanel.IndicatorInsertion added = TradingPanel.insertIndicator(
                StrategyScript.starter(), "price_zscore_20");
        assertTrue(added.added());
        assertTrue(added.script().contains("input price_zscore_20 Indicator(price_zscore_20)"));
        assertEquals("custom", StrategyScript.parse(added.script()).inputs().get(2).kind());
        assertFalse(TradingPanel.insertIndicator(added.script(), "price_zscore_20").added());
        TradingPanel.IndicatorInsertion collision = TradingPanel.insertIndicator(
                StrategyScript.starter(), "close");
        assertEquals("close_2", collision.alias());
        StrategyScript.parse(collision.script());
    }
}
