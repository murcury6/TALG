package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChartPresetsTest {
    @Test void everyStarterIsAValidLiveChartScriptWithoutEmbeddedPrices() {
        for (ChartPresets.Preset preset : ChartPresets.Preset.values()) {
            String source = ChartPresets.script(preset, "MSFT", List.of("MSFT", "NVDA", "AAPL"));
            ChartScript.Config chart = ChartScript.parse(source);
            assertEquals("MSFT", chart.ticker());
            assertFalse(source.contains("price 100"));
            if (preset == ChartPresets.Preset.SEPARATE || preset == ChartPresets.Preset.RELATIVE)
                assertEquals("NVDA", chart.comparisons());
        }
    }
}
