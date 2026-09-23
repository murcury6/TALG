package io.github.murcury6.talg;

import java.util.List;
import java.util.Locale;

/** Readable chart starters. They configure live requests, never embed price data. */
final class ChartPresets {
    private ChartPresets() {}

    enum Preset {
        TREND("Trend + bands"),
        RELATIVE("Two-stock comparison"),
        SEPARATE("Two separate stocks"),
        INTRADAY("Intraday volatility"),
        CANDLES("Candles + volume"),
        PRICE_VOLUME("Price + volume"),
        VOLUME("Volume + average");

        private final String label;
        Preset(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    static String script(Preset preset, String selectedTicker, List<String> watchlist) {
        String chosen = validTicker(selectedTicker);
        String primary = chosen == null ? "AAPL" : chosen;
        String second = watchlist == null ? null : watchlist.stream()
                .map(ChartPresets::validTicker)
                .filter(ticker -> ticker != null && !ticker.equals(primary))
                .findFirst().orElse(null);
        if (second == null) second = primary.equals("MSFT") ? "AAPL" : "MSFT";
        String common = "# Edit any line before applying this panel. No prices are embedded.\n"
                + "ticker " + primary + "\n";
        return switch (preset) {
            case TREND -> common + "range 6M\nbars 1Day\nrefresh off\nlayout overlay\n"
                    + "plot close\ndisplay line\n"
                    + "overlay SMA(20)\noverlay EMA(50)\noverlay Bollinger(20, 2)\n"
                    + "study RSI(14)\n";
            case RELATIVE -> common + "stock " + second + "\nrange 3M\nbars 1Day\n"
                    + "refresh off\nlayout overlay\nplot close\ndisplay line\nstudy return\n";
            case SEPARATE -> common + "stock " + second + "\nrange 3M\nbars 1Day\n"
                    + "refresh off\nlayout separate\nplot close\ndisplay line\noverlay SMA(10)\noverlay EMA(20)\n"
                    + "study MACD(12, 26, 9)\n";
            case INTRADAY -> common + "range 5D\nbars 5Min\nrefresh 60s\n"
                    + "layout overlay\nplot close\ndisplay line\noverlay EMA(20)\nstudy Volatility(20)\n";
            case CANDLES -> common + "range 3M\nbars 1Day\nrefresh 60s\n"
                    + "layout overlay\nplot close\ndisplay candles\nstudy Field(volume)\n";
            case PRICE_VOLUME -> common + "range 3M\nbars 1Day\nrefresh off\n"
                    + "layout overlay\nplot close\ndisplay line\noverlay SMA(20)\nstudy Field(volume)\n";
            case VOLUME -> common + "range 3M\nbars 1Day\nrefresh off\n"
                    + "layout overlay\nplot volume\ndisplay columns\noverlay SMA(20)\nstudy none\n";
        };
    }

    private static String validTicker(String value) {
        if (value == null) return null;
        String ticker = value.trim().toUpperCase(Locale.ROOT);
        return ticker.matches("[A-Z][A-Z0-9.-]{0,9}") ? ticker : null;
    }
}
