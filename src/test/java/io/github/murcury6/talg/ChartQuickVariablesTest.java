package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChartQuickVariablesTest {
    private static final String ACTIVE = ChartScript.template("AAPL");
    private static final String OVERLAYS = "SMA(20)\nEMA(20)\nBollinger(20, 2)";

    @Test void changingTheWindowAdaptsBarWidthAndChangingBarWidthAdaptsTheWindow() {
        ChartQuickVariables.Resolution shortWindow = ChartQuickVariables.resolve(ACTIVE,
                request("AAPL", "1D", "1Day", "close", "line", "RSI(14)", "", OVERLAYS,
                        "range", ""));
        ChartScript.Config oneDay = ChartScript.parse(shortWindow.script());
        assertEquals("1D", oneDay.range());
        assertEquals("1Hour", oneDay.bars());
        assertFalse(shortWindow.adjustments().isEmpty());

        ChartQuickVariables.Resolution minuteBars = ChartQuickVariables.resolve(ACTIVE,
                request("AAPL", "3M", "1Min", "close", "line", "RSI(14)", "", OVERLAYS,
                        "bars", ""));
        ChartScript.Config minute = ChartScript.parse(minuteBars.script());
        assertEquals("1Min", minute.bars());
        assertEquals("5D", minute.range());
    }

    @Test void displayAndPlotFollowWhicheverVariableWasChanged() {
        ChartQuickVariables.Resolution candles = ChartQuickVariables.resolve(ACTIVE,
                request("AAPL", "3M", "1Day", "volume", "candles", "RSI(14)", "", OVERLAYS,
                        "", "display"));
        assertEquals("close", ChartScript.parse(candles.script()).plot());
        assertEquals("candles", ChartScript.parse(candles.script()).display());

        ChartQuickVariables.Resolution volume = ChartQuickVariables.resolve(ACTIVE,
                request("AAPL", "3M", "1Day", "volume", "candles", "RSI(14)", "", OVERLAYS,
                        "", "plot"));
        assertEquals("volume", ChartScript.parse(volume.script()).plot());
        assertEquals("columns", ChartScript.parse(volume.script()).display());
    }

    @Test void incompleteTextKeepsAValidConfigurationAndNormalizesKnownIndicators() {
        ChartQuickVariables.Resolution resolved = ChartQuickVariables.resolve(ACTIVE,
                request("???", "3M", "1Day", "unknown", "line", "RSI(0)",
                        "MSFT, MSFT, AAPL, BAD!, NVDA", "SMA(0)\nEMA(900)\nBollinger(20, 0)\nwhat",
                        "", ""));
        ChartScript.Config chart = ChartScript.parse(resolved.script());
        assertEquals("AAPL", chart.ticker());
        assertEquals("close", chart.plot());
        assertEquals("MSFT,NVDA", chart.comparisons());
        assertEquals(2, chart.rsi());
        assertEquals(2, chart.sma());
        assertEquals(500, chart.ema());
        assertEquals(0.1, chart.sigma());
        assertTrue(resolved.adjustments().size() >= 3);
    }

    @Test void everyQuickRangeBarAndStyleCombinationResolvesToAParsableScript() {
        for (String range : new String[]{"1D", "5D", "1M", "3M", "6M", "YTD", "1Y"})
            for (String bars : new String[]{"1Min", "5Min", "15Min", "1Hour", "1Day", "1Week"})
                for (String style : new String[]{"line", "columns", "candles", "hollow_candles", "ohlc", "heikin_ashi"}) {
                    ChartQuickVariables.Resolution resolved = ChartQuickVariables.resolve(ACTIVE,
                            request("AAPL", range, bars, "volume", style, "none", "", "",
                                    "range", "display"));
                    ChartScript.parse(resolved.script());
                }
    }

    private static ChartQuickVariables.Request request(String ticker, String range, String bars,
                                                        String plot, String display, String study,
                                                        String comparisons, String overlays,
                                                        String timePriority, String valuePriority) {
        return new ChartQuickVariables.Request(ticker, range, bars, plot, display, study,
                "60s", "overlay", comparisons, overlays, false,
                timePriority, valuePriority);
    }
}
