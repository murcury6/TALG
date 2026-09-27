package io.github.murcury6.talg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns quick-variable edits into a valid chart configuration, resolving linked choices. */
final class ChartQuickVariables {
    private static final List<String> RANGES = List.of("1D", "5D", "1M", "3M", "6M", "YTD", "1Y");
    private static final List<String> BARS = List.of("1Min", "5Min", "15Min", "1Hour", "1Day", "1Week");
    private static final Set<String> FIELDS = Set.of("open", "high", "low", "close", "volume", "vwap", "trades");
    private static final Set<String> PRICE_BARS = Set.of("candles", "hollow_candles", "ohlc", "heikin_ashi");
    private static final Set<String> DISPLAYS = Set.of("line", "step", "area", "points", "columns",
            "lollipop", "candles", "hollow_candles", "ohlc", "heikin_ashi");
    private static final Pattern CUSTOM = Pattern.compile("(?i)^(?:indicator|custom)\\s*\\(\\s*([a-z][a-z0-9_]{0,39})\\s*\\)$");
    private static final Pattern SINGLE_PERIOD = Pattern.compile("(?i)^(sma|ema|rsi|volatility)\\s*\\(\\s*(-?\\d+)\\s*\\)$");
    private static final Pattern BOLLINGER = Pattern.compile("(?i)^bollinger\\s*\\(\\s*(-?\\d+)\\s*,\\s*(-?[0-9.]+)\\s*\\)$");
    private static final Pattern MACD = Pattern.compile("(?i)^macd\\s*\\(\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)$");
    private static final Pattern FIELD = Pattern.compile("(?i)^field\\s*\\(\\s*([a-z]+)\\s*\\)$");

    private ChartQuickVariables() {}

    record Request(String ticker, String range, String bars, String plot, String display, String study,
                   String refresh, String layout, String comparisons, String overlays,
                   boolean watermark,
                   String timePriority, String valuePriority) {}

    record Resolution(String script, List<String> adjustments) {
        Resolution { adjustments = List.copyOf(new LinkedHashSet<>(adjustments)); }
    }

    static Resolution resolve(String activeScript, Request request) {
        ChartScript.Config active = ChartScript.parse(activeScript);
        List<String> notes = new ArrayList<>();
        String ticker = ticker(request.ticker());
        if (ticker == null) {
            ticker = active.ticker();
            notes.add("Kept " + ticker + "; ticker text was incomplete.");
        }
        String range = option(request.range(), RANGES, active.range());
        String bars = option(request.bars(), BARS, active.bars());
        if (!compatible(range, bars)) {
            boolean keepRange = "range".equals(request.timePriority())
                    || !range.equals(active.range()) && bars.equals(active.bars());
            if (keepRange) {
                String before = bars;
                String selectedRange = range;
                bars = nearestCompatible(bars, BARS, option -> compatible(selectedRange, option));
                notes.add("Bar width changed from " + before + " to " + bars + " for " + range + ".");
            } else {
                String before = range;
                String selectedBars = bars;
                range = nearestCompatible(range, RANGES, option -> compatible(option, selectedBars));
                notes.add("Window changed from " + before + " to " + range + " for " + bars + " bars.");
            }
        }
        String previousPlot = line(activeScript, "plot", "close");
        String plot = plot(request.plot());
        if (plot == null) {
            plot = previousPlot;
            notes.add("Kept the prior plot value; the new name is not a bar field or saved custom value.");
        }
        String display = option(request.display(), DISPLAYS, active.display());
        if (PRICE_BARS.contains(display) && !"close".equalsIgnoreCase(plot)) {
            if ("plot".equals(request.valuePriority())) {
                display = Set.of("volume", "trades").contains(plot) ? "columns" : "line";
                notes.add("Display changed to " + display + " for the selected plot value.");
            } else {
                plot = "close";
                notes.add("Plot value changed to close for " + display.replace('_', ' ') + ".");
            }
        }
        String previousStudy = line(activeScript, "study", "none");
        String study = study(request.study(), active, previousStudy, notes);
        String refresh = option(request.refresh(), Set.of("off", "30s", "60s", "5m", "15m"),
                active.refreshSeconds() == 0 ? "off" : line(activeScript, "refresh", "60s"));
        String layout = option(request.layout(), Set.of("overlay", "separate"), active.layout());
        List<String> comparisons = comparisons(request.comparisons(), ticker, active, notes);
        List<String> overlays = overlays(request.overlays(), activeScript, active, notes);

        String updated = activeScript;
        updated = setLine(updated, "ticker", ticker);
        updated = setLine(updated, "range", range);
        updated = setLine(updated, "bars", bars);
        updated = setLine(updated, "plot", plot);
        updated = setLine(updated, "display", display);
        updated = setLine(updated, "study", study);
        updated = setLine(updated, "refresh", refresh);
        updated = setLine(updated, "layout", layout);
        updated = setLine(updated, "watermark", request.watermark() ? "on" : "off");
        updated = updated.replaceAll("(?m)^[ \\t]*(?:overlay|compare|stock)[ \\t]+[^\\r\\n]*(?:\\R)?", "");
        StringBuilder result = new StringBuilder(updated.stripTrailing());
        for (String comparison : comparisons) result.append("\ncompare ").append(comparison);
        for (String overlay : overlays) result.append("\noverlay ").append(overlay);
        result.append('\n');
        try {
            ChartScript.parse(result.toString());
            return new Resolution(result.toString(), notes);
        } catch (IllegalArgumentException unsupported) {
            // A saved valid chart is preferable to a broken quick edit if a future rule changes.
            notes.add("Kept the previous chart settings; this combination is not supported.");
            return new Resolution(activeScript, notes);
        }
    }

    private static String ticker(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return value.matches("[A-Z][A-Z0-9.-]{0,9}") ? value : null;
    }

    private static String option(String value, List<String> options, String fallback) {
        return options.stream().filter(option -> option.equalsIgnoreCase(value == null ? "" : value.trim()))
                .findFirst().orElse(fallback);
    }

    private static String option(String value, Set<String> options, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return options.contains(normalized) ? normalized : fallback;
    }

    private static boolean compatible(String range, String bars) {
        int days = RANGES.indexOf(range);
        return switch (bars) {
            case "1Min" -> days <= 1;
            case "5Min" -> days <= 2;
            case "15Min" -> days <= 3;
            case "1Day" -> days >= 1;
            case "1Week" -> days >= 2;
            default -> true;
        };
    }

    private static String nearestCompatible(String requested, List<String> ordered,
                                            java.util.function.Predicate<String> allowed) {
        int index = ordered.indexOf(requested);
        return ordered.stream().filter(allowed)
                .min(java.util.Comparator.comparingInt(value -> Math.abs(ordered.indexOf(value) - index)))
                .orElse(requested);
    }

    private static String plot(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        value = switch (value) {
            case "price", "last", "last price" -> "close";
            case "vol", "shares" -> "volume";
            case "trade count" -> "trades";
            default -> value;
        };
        if (FIELDS.contains(value)) return value;
        Matcher custom = CUSTOM.matcher(value);
        return custom.matches() && savedIndicator(custom.group(1))
                ? "Indicator(" + custom.group(1) + ")" : null;
    }

    private static String study(String raw, ChartScript.Config active, String previous, List<String> notes) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("none")) return "none";
        if (value.equalsIgnoreCase("return")) return "return";
        if (value.equalsIgnoreCase("rsi")) return "RSI(" + active.rsi() + ")";
        if (value.equalsIgnoreCase("macd"))
            return "MACD(" + active.macdFast() + "," + active.macdSlow() + "," + active.macdSignal() + ")";
        if (value.equalsIgnoreCase("volatility")) return "Volatility(" + active.volatility() + ")";
        if (FIELDS.contains(value.toLowerCase(Locale.ROOT)))
            return "Field(" + value.toLowerCase(Locale.ROOT) + ")";
        Matcher single = SINGLE_PERIOD.matcher(value);
        if (single.matches() && Set.of("rsi", "volatility").contains(single.group(1).toLowerCase(Locale.ROOT))) {
            int period = boundedInteger(single.group(2));
            if (period >= 0) {
                if (period != Integer.parseInt(single.group(2))) notes.add("Study period adjusted to 2–500 bars.");
                return single.group(1).toUpperCase(Locale.ROOT) + "(" + period + ")";
            }
        }
        Matcher macd = MACD.matcher(value);
        if (macd.matches()) {
            int fast = boundedInteger(macd.group(1));
            int slow = boundedInteger(macd.group(2));
            int signal = boundedInteger(macd.group(3));
            if (fast >= 0 && slow >= 0 && signal >= 0) {
                if (fast >= slow) {
                    slow = Math.min(500, fast + 1);
                    fast = Math.min(fast, slow - 1);
                    notes.add("MACD slow period raised above the fast period.");
                }
                return "MACD(" + fast + "," + slow + "," + signal + ")";
            }
        }
        Matcher field = FIELD.matcher(value);
        if (field.matches() && FIELDS.contains(field.group(1).toLowerCase(Locale.ROOT)))
            return "Field(" + field.group(1).toLowerCase(Locale.ROOT) + ")";
        Matcher custom = CUSTOM.matcher(value);
        if (custom.matches() && savedIndicator(custom.group(1)))
            return "Indicator(" + custom.group(1).toLowerCase(Locale.ROOT) + ")";
        notes.add("Kept the prior lower study; the requested study is not available.");
        return previous;
    }

    private static List<String> comparisons(String raw, String primary, ChartScript.Config active,
                                             List<String> notes) {
        if (raw == null || raw.isBlank()) return List.of();
        LinkedHashSet<String> valid = new LinkedHashSet<>();
        boolean adjusted = false;
        for (String item : raw.split(",")) {
            String symbol = ticker(item);
            if (symbol == null || symbol.equals(primary) || valid.contains(symbol) || valid.size() == 4) {
                adjusted = true;
                continue;
            }
            valid.add(symbol);
        }
        if (valid.isEmpty()) {
            if (adjusted) notes.add("Kept prior comparisons; no valid new comparison ticker was entered.");
            return active.comparisons().isBlank() ? List.of() :
                    java.util.Arrays.stream(active.comparisons().split(","))
                            .filter(symbol -> !symbol.equals(primary)).toList();
        }
        if (adjusted) notes.add("Only distinct comparison tickers were kept (up to four).");
        return List.copyOf(valid);
    }

    private static List<String> overlays(String raw, String activeScript, ChartScript.Config active,
                                          List<String> notes) {
        if (raw == null || raw.isBlank()) return List.of();
        LinkedHashMap<String, String> valid = new LinkedHashMap<>();
        boolean adjusted = false;
        for (String line : raw.split("\\R")) {
            String value = line.trim().replaceFirst("(?i)^overlay\\s+", "");
            if (value.isEmpty()) continue;
            String normalized = overlay(value, active);
            if (normalized == null) {
                adjusted = true;
            } else {
                if (!normalized.replace(" ", "").equalsIgnoreCase(value.replace(" ", "")))
                    notes.add("Filled or adjusted an overlay's parameters.");
                String key = normalized.toLowerCase(Locale.ROOT).startsWith("custom(")
                        ? normalized.toLowerCase(Locale.ROOT)
                        : normalized.substring(0, normalized.indexOf('(')).toLowerCase(Locale.ROOT);
                if (valid.putIfAbsent(key, normalized) != null) adjusted = true;
            }
        }
        if (valid.isEmpty() && adjusted) {
            notes.add("Kept prior overlays; the new overlay name was not available.");
            return lines(activeScript, "overlay");
        }
        if (adjusted) notes.add("Unsupported or repeated overlays were left out.");
        return List.copyOf(valid.values());
    }

    private static String overlay(String raw, ChartScript.Config active) {
        String value = raw.trim();
        if (value.equalsIgnoreCase("sma")) return "SMA(" + active.sma() + ")";
        if (value.equalsIgnoreCase("ema")) return "EMA(" + active.ema() + ")";
        if (value.equalsIgnoreCase("bollinger"))
            return "Bollinger(" + active.bollinger() + "," + active.sigma() + ")";
        Matcher single = SINGLE_PERIOD.matcher(value);
        if (single.matches() && Set.of("sma", "ema").contains(single.group(1).toLowerCase(Locale.ROOT))) {
            int period = boundedInteger(single.group(2));
            return period < 0 ? null : single.group(1).toUpperCase(Locale.ROOT) + "(" + period + ")";
        }
        Matcher bollinger = BOLLINGER.matcher(value);
        if (bollinger.matches()) {
            int period = boundedInteger(bollinger.group(1));
            try {
                double sigma = Math.min(10, Math.max(0.1, Double.parseDouble(bollinger.group(2))));
                return period < 0 || !Double.isFinite(sigma) ? null
                        : "Bollinger(" + period + "," + sigma + ")";
            } catch (NumberFormatException ignored) { return null; }
        }
        Matcher custom = CUSTOM.matcher(value);
        return custom.matches() && savedIndicator(custom.group(1))
                ? "Indicator(" + custom.group(1).toLowerCase(Locale.ROOT) + ")" : null;
    }

    private static int boundedInteger(String raw) {
        try { return Math.min(500, Math.max(2, Integer.parseInt(raw))); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private static boolean savedIndicator(String name) {
        return Files.isRegularFile(Path.of("work", "indicators", name.toLowerCase(Locale.ROOT) + ".R"));
    }

    private static String line(String source, String command, String fallback) {
        Matcher found = Pattern.compile("(?m)^[ \\t]*" + command + "[ \\t]+([^\\r\\n]+)").matcher(source);
        return found.find() ? found.group(1).trim() : fallback;
    }

    private static List<String> lines(String source, String command) {
        return source.lines().map(String::trim).filter(row -> row.startsWith(command + " "))
                .map(row -> row.substring(command.length()).trim()).toList();
    }

    private static String setLine(String source, String command, String value) {
        String pattern = "(?m)^[ \\t]*" + command + "[ \\t]+[^\\r\\n]*";
        if (Pattern.compile(pattern).matcher(source).find())
            return source.replaceFirst(pattern, Matcher.quoteReplacement(command + " " + value));
        return source.stripTrailing() + "\n" + command + " " + value + "\n";
    }
}
