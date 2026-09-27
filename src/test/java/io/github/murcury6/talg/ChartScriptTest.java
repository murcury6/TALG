package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChartScriptTest {
    @Test void parsesOnePanelWithCustomStudiesAndComparisons() {
        ChartScript.Config chart = ChartScript.parse("# a research view\n"
                + "ticker aapl\nrange 5D\nbars 5Min\nrefresh 60s\n"
                + "overlay SMA(8)\noverlay EMA(13)\noverlay Bollinger(21, 2.5)\n"
                + "compare MSFT\ncompare NVDA\nstudy MACD(8,21,5)\n");
        assertEquals("AAPL", chart.ticker());
        assertEquals("MSFT,NVDA", chart.comparisons());
        assertEquals("sma,ema,bollinger", chart.overlays());
        assertEquals("8,13,21,2.5,14,8,21,5,20", chart.parameterArgument());
    }

    @Test void rejectsTyposAndUnsupportedRequests() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ChartScript.parse(ChartScript.template("AAPL").replace("range 3M", "range 1Y")
                        .replace("bars 1Day", "bars 1Min"))).getMessage().contains("too many"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ChartScript.parse(ChartScript.template("AAPL") + "overaly RSI(8)\n"))
                .getMessage().contains("Line 14"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ChartScript.parse(ChartScript.template("AAPL") + "compare AAPL\n"))
                .getMessage().contains("primary ticker"));
    }

    @Test void supportsSeveralIndependentStocksInOnePanel() {
        ChartScript.Config chart = ChartScript.parse(ChartScript.template("AAPL")
                .replace("layout overlay", "layout separate")
                + "stock MSFT\nstock NVDA\ncompare GOOG\n");
        assertEquals("separate", chart.layout());
        assertEquals("MSFT,NVDA,GOOG", chart.comparisons());
    }

    @Test void referencesUserAuthoredIndicatorsBySafeNames() {
        ChartScript.Config chart = ChartScript.parse(ChartScript.template("AAPL")
                .replace("study RSI(14)", "study Indicator(my_oscillator)")
                + "overlay Indicator(my_band)\noverlay Indicator(my_signal)\n");
        assertEquals("custom", chart.study());
        assertEquals("my_oscillator", chart.customStudy());
        assertEquals("my_band,my_signal", chart.customOverlays());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ChartScript.parse(ChartScript.template("AAPL")
                        + "overlay Indicator(../bad)\n")).getMessage().contains("Indicator name"));
        assertEquals("my_oscillator", ChartScript.parse(ChartScript.template("AAPL")
                .replace("study RSI(14)", "study Custom(my_oscillator)")).customStudy());
    }

    @Test void plotsObservedBarFieldsAndUserAuthoredValues() {
        ChartScript.Config volume = ChartScript.parse(ChartScript.template("AAPL")
                .replace("plot close", "plot volume")
                .replace("study RSI(14)", "study Field(trades)"));
        assertEquals("volume", volume.plot());
        assertEquals("field", volume.study());
        assertEquals("trades", volume.customStudy());

        ChartScript.Config custom = ChartScript.parse(ChartScript.template("AAPL")
                .replace("plot close", "plot Indicator(risk_score)"));
        assertEquals("custom:risk_score", custom.plot());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ChartScript.parse(ChartScript.template("AAPL")
                        .replace("plot close", "plot imaginary"))).getMessage().contains("Line 7"));
    }

    @Test void supportsCommonDisplayStylesAndRejectsIncompatiblePriceBars() {
        for (String style : new String[]{"line", "step", "area", "points", "columns", "lollipop",
                "candles", "hollow_candles", "ohlc", "heikin_ashi"}) {
            ChartScript.Config chart = ChartScript.parse(ChartScript.template("AAPL")
                    .replace("display line", "display " + style));
            assertEquals(style, chart.display());
        }
        assertEquals("line", ChartScript.parse(ChartScript.template("AAPL")
                .replace("display line\n", "")).display());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ChartScript.parse(ChartScript.template("AAPL")
                        .replace("display line", "display candles")
                        .replace("plot close", "plot volume"))).getMessage().contains("require plot close"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ChartScript.parse(ChartScript.template("AAPL")
                        .replace("display line", "display imaginary"))).getMessage().contains("Display must"));
    }

    @Test void symbolWatermarkIsOptionalAndValidated() {
        assertTrue(ChartScript.parse(ChartScript.template("AAPL")
                .replace("watermark off", "watermark on")).watermark());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ChartScript.parse(ChartScript.template("AAPL")
                        .replace("watermark off", "watermark maybe")))
                .getMessage().contains("watermark must be on or off"));
    }
}
