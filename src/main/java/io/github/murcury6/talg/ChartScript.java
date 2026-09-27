package io.github.murcury6.talg;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A deliberately small, declarative chart language; no executable statements. */
final class ChartScript {
    private static final Pattern CALL = Pattern.compile("(?i)^([a-z]+)\\s*\\(([^()]*)\\)$");
    private static final Set<String> RANGES = Set.of("1D", "5D", "1M", "3M", "6M", "YTD", "1Y");
    private static final Set<String> BARS = Set.of("1Min", "5Min", "15Min", "1Hour", "1Day", "1Week");
    private static final Set<String> FIELDS = Set.of("open", "high", "low", "close", "volume", "vwap", "trades");

    private ChartScript() {}

    static String template(String ticker) {
        return "# Periods are measured in bars, not calendar days.\n"
                + "ticker " + (ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT)) + "\n"
                + "range 3M\n"
                + "bars 1Day\n"
                + "refresh 60s\n"
                + "layout overlay\n"
                + "plot close\n"
                + "display line\n"
                + "watermark off\n"
                + "overlay SMA(20)\n"
                + "overlay EMA(20)\n"
                + "overlay Bollinger(20, 2)\n"
                + "study RSI(14)\n";
    }

    static Config parse(String source) {
        String ticker = null, range = null, bars = null, study = null, layout = "overlay", plot = "close", display = "line";
        boolean watermark = false;
        Integer refresh = null;
        Set<String> comparisons = new LinkedHashSet<>();
        Set<String> customOverlays = new LinkedHashSet<>();
        String customStudy = "";
        int sma = 20, ema = 20, bollinger = 20, rsi = 14;
        int macdFast = 12, macdSlow = 26, macdSignal = 9, volatility = 20;
        double sigma = 2;
        boolean useSma = false, useEma = false, useBollinger = false;
        Set<String> seen = new HashSet<>();
        String[] lines = source.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+", 2);
            if (parts.length != 2 || parts[1].isBlank()) fail(i, "Expected a command and value");
            String command = parts[0].toLowerCase(Locale.ROOT);
            String value = parts[1].trim();
            try {
                switch (command) {
                    case "ticker" -> {
                        unique(seen, "ticker");
                        ticker = value.toUpperCase(Locale.ROOT);
                        if (!ticker.matches("[A-Z][A-Z0-9.-]{0,9}")) throw new IllegalArgumentException("Invalid ticker");
                    }
                    case "range" -> {
                        unique(seen, "range");
                        range = value.toUpperCase(Locale.ROOT);
                        if (!RANGES.contains(range)) throw new IllegalArgumentException("Range must be 1D, 5D, 1M, 3M, 6M, YTD or 1Y");
                    }
                    case "bars" -> {
                        unique(seen, "bars");
                        bars = BARS.stream().filter(option -> option.equalsIgnoreCase(value)).findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("Bars must be 1Min, 5Min, 15Min, 1Hour, 1Day or 1Week"));
                    }
                    case "refresh" -> {
                        unique(seen, "refresh");
                        refresh = switch (value.toLowerCase(Locale.ROOT)) {
                            case "off" -> 0;
                            case "30s" -> 30;
                            case "60s" -> 60;
                            case "5m" -> 300;
                            case "15m" -> 900;
                            default -> throw new IllegalArgumentException("Refresh must be off, 30s, 60s, 5m or 15m");
                        };
                    }
                    case "layout" -> {
                        unique(seen, "layout");
                        layout = value.toLowerCase(Locale.ROOT);
                        if (!Set.of("overlay", "separate").contains(layout))
                            throw new IllegalArgumentException("Layout must be overlay or separate");
                    }
                    case "plot" -> {
                        unique(seen, "plot");
                        if (FIELDS.contains(value.toLowerCase(Locale.ROOT))) {
                            plot = value.toLowerCase(Locale.ROOT);
                        } else {
                            Matcher call = call(value);
                            if (!Set.of("custom", "indicator").contains(call.group(1).toLowerCase(Locale.ROOT)))
                                throw new IllegalArgumentException("Plot must be open, high, low, close, volume, vwap, trades or Indicator(name)");
                            String[] args = arguments(call);
                            exact(args, 1);
                            plot = "custom:" + customName(args[0]);
                        }
                    }
                    case "display" -> {
                        unique(seen, "display");
                        display = value.toLowerCase(Locale.ROOT);
                        if (!Set.of("line", "step", "area", "points", "columns", "lollipop",
                                "candles", "hollow_candles", "ohlc", "heikin_ashi").contains(display))
                            throw new IllegalArgumentException("Display must be line, step, area, points, columns, lollipop, candles, hollow_candles, ohlc or heikin_ashi");
                    }
                    case "watermark" -> {
                        unique(seen, "watermark");
                        watermark = toggle(value, "watermark");
                    }
                    case "compare", "stock" -> {
                        String other = value.toUpperCase(Locale.ROOT);
                        if (!other.matches("[A-Z][A-Z0-9.-]{0,9}")) throw new IllegalArgumentException("Invalid stock ticker");
                        if (!comparisons.add(other)) throw new IllegalArgumentException("Duplicate stock ticker");
                        if (comparisons.size() > 4) throw new IllegalArgumentException("A panel supports up to five stocks total");
                    }
                    case "overlay" -> {
                        Matcher call = call(value);
                        String name = call.group(1).toLowerCase(Locale.ROOT);
                        unique(seen, "overlay " + (name.equals("custom") ? "indicator" : name)
                                + (Set.of("custom", "indicator").contains(name)
                                ? ":" + call.group(2).toLowerCase(Locale.ROOT) : ""));
                        String[] args = arguments(call);
                        switch (name) {
                            case "sma" -> { exact(args, 1); sma = period(args[0]); useSma = true; }
                            case "ema" -> { exact(args, 1); ema = period(args[0]); useEma = true; }
                            case "bollinger" -> {
                                exact(args, 2);
                                bollinger = period(args[0]);
                                sigma = Double.parseDouble(args[1]);
                                if (!Double.isFinite(sigma) || sigma <= 0 || sigma > 10)
                                    throw new IllegalArgumentException("Bollinger multiplier must be above 0 and at most 10");
                                useBollinger = true;
                            }
                            case "custom", "indicator" -> {
                                exact(args, 1);
                                customOverlays.add(customName(args[0]));
                            }
                            default -> throw new IllegalArgumentException("Unknown overlay: " + name);
                        }
                    }
                    case "study" -> {
                        unique(seen, "study");
                        if (value.equalsIgnoreCase("none") || value.equalsIgnoreCase("return")) {
                            study = value.toLowerCase(Locale.ROOT);
                        } else {
                            Matcher call = call(value);
                            study = call.group(1).toLowerCase(Locale.ROOT);
                            if (study.equals("indicator")) study = "custom";
                            String[] args = arguments(call);
                            switch (study) {
                                case "rsi" -> { exact(args, 1); rsi = period(args[0]); }
                                case "volatility" -> { exact(args, 1); volatility = period(args[0]); }
                                case "macd" -> {
                                    exact(args, 3);
                                    macdFast = period(args[0]);
                                    macdSlow = period(args[1]);
                                    macdSignal = period(args[2]);
                                    if (macdFast >= macdSlow) throw new IllegalArgumentException("MACD fast period must be below slow period");
                                }
                                case "custom" -> {
                                    exact(args, 1);
                                    customStudy = customName(args[0]);
                                }
                                case "field" -> {
                                    exact(args, 1);
                                    customStudy = args[0].toLowerCase(Locale.ROOT);
                                    if (!FIELDS.contains(customStudy))
                                        throw new IllegalArgumentException("Unknown Alpaca bar field: " + customStudy);
                                }
                                default -> throw new IllegalArgumentException("Unknown study: " + study);
                            }
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown command: " + command);
                }
            } catch (IllegalArgumentException error) {
                fail(i, error.getMessage());
            }
        }
        if (ticker == null) throw new IllegalArgumentException("Add a ticker line, for example: ticker AAPL");
        if (range == null) throw new IllegalArgumentException("Add a range line, for example: range 3M");
        if (bars == null) throw new IllegalArgumentException("Add a bars line, for example: bars 1Day");
        if (refresh == null) throw new IllegalArgumentException("Add a refresh line, for example: refresh off");
        if (study == null) throw new IllegalArgumentException("Add one study line, for example: study RSI(14)");
        if (comparisons.contains(ticker)) throw new IllegalArgumentException("A comparison ticker cannot also be the primary ticker.");
        if (Set.of("candles", "hollow_candles", "ohlc", "heikin_ashi").contains(display) && !"close".equals(plot))
            throw new IllegalArgumentException("Price-bar displays require plot close so open, high, low and close use the same price axis.");
        if (bars.equals("1Min") && !Set.of("1D", "5D").contains(range)
                || bars.equals("5Min") && !Set.of("1D", "5D", "1M").contains(range)
                || bars.equals("15Min") && !Set.of("1D", "5D", "1M", "3M").contains(range)) {
            throw new IllegalArgumentException("That bar width and range would request too many observations; choose a shorter range or wider bars.");
        }
        if (bars.equals("1Day") && range.equals("1D")
                || bars.equals("1Week") && Set.of("1D", "5D").contains(range)) {
            throw new IllegalArgumentException("That bar width gives fewer than two observations; choose a longer range or narrower bars.");
        }
        String overlays = String.join(",", useSma ? "sma" : "", useEma ? "ema" : "",
                useBollinger ? "bollinger" : "").replaceAll("(^,+|,+$)", "").replaceAll(",+", ",");
        return new Config(ticker, range, bars, refresh, layout, plot, display,
                watermark, String.join(",", comparisons), overlays,
                String.join(",", customOverlays), study, customStudy, sma, ema, bollinger,
                sigma, rsi, macdFast, macdSlow, macdSignal, volatility);
    }

    private static Matcher call(String value) {
        Matcher matcher = CALL.matcher(value);
        if (!matcher.matches()) throw new IllegalArgumentException("Expected a name with parameters, such as SMA(20)");
        return matcher;
    }

    private static String[] arguments(Matcher call) {
        return call.group(2).split("\\s*,\\s*", -1);
    }

    private static void exact(String[] args, int count) {
        if (args.length != count) throw new IllegalArgumentException("Expected " + count + " parameter(s)");
    }

    private static int period(String value) {
        int number = Integer.parseInt(value);
        if (number < 2 || number > 500) throw new IllegalArgumentException("Indicator periods must be from 2 to 500 bars");
        return number;
    }

    private static String customName(String value) {
        String name = value.toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z][a-z0-9_]{0,39}"))
            throw new IllegalArgumentException("Indicator name must be 1–40 letters, digits or underscores, starting with a letter");
        return name;
    }

    private static boolean toggle(String value, String command) {
        if (value.equalsIgnoreCase("on")) return true;
        if (value.equalsIgnoreCase("off")) return false;
        throw new IllegalArgumentException(command + " must be on or off");
    }

    private static void unique(Set<String> seen, String key) {
        if (!seen.add(key)) throw new IllegalArgumentException("Duplicate " + key + " command");
    }

    private static void fail(int zeroBasedLine, String message) {
        throw new IllegalArgumentException("Line " + (zeroBasedLine + 1) + ": " + message);
    }

    record Config(String ticker, String range, String bars, int refreshSeconds, String layout, String plot,
                  String display, boolean watermark,
                  String comparisons,
                  String overlays, String customOverlays, String study, String customStudy,
                  int sma, int ema, int bollinger,
                  double sigma, int rsi, int macdFast, int macdSlow, int macdSignal,
                  int volatility) {
        String parameterArgument() {
            return sma + "," + ema + "," + bollinger + "," + sigma + "," + rsi + ","
                    + macdFast + "," + macdSlow + "," + macdSignal + "," + volatility;
        }
    }
}
