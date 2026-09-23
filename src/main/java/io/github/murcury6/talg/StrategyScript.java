package io.github.murcury6.talg;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded declarative strategy language. Expressions cannot call Java, R, or network APIs. */
final class StrategyScript {
    private static final Pattern INPUT = Pattern.compile(
            "(?i)^([a-z][a-z0-9_]{0,39})\\s+(Custom|Model)\\(([a-z][a-z0-9_]{0,39})(?:,\\s*([a-z][a-z0-9_]{0,39}))?\\)$");
    private static final Pattern LET = Pattern.compile("(?i)^([a-z][a-z0-9_]{0,39})\\s*=\\s*(.+)$");
    private static final Set<String> BASE = Set.of("open", "high", "low", "close", "volume", "vwap", "trades");

    private StrategyScript() {}

    static String starter() {
        return "# Preview first. A true rule never submits an order by itself.\n"
                + "name Trend Volume\n"
                + "ticker AAPL\nrange 6M\nbars 1Day\n"
                + "input trend Custom(ema_34)\n"
                + "input pressure Custom(volume_ratio_20)\n"
                + "let edge = close / trend - 1\n"
                + "buy edge > 0.01 and pressure > 1.10\n"
                + "sell edge < -0.01\n"
                + "qty 1\n";
    }

    static Parsed parse(String source) {
        if (source == null || source.length() > 30_000)
            throw new IllegalArgumentException("Strategy script must be at most 30,000 characters.");
        String name = null, ticker = null, range = null, bars = null;
        int quantity = 0;
        List<Input> inputs = new ArrayList<>();
        List<Assignment> assignments = new ArrayList<>();
        Expr buy = null, sell = null;
        Set<String> names = new HashSet<>(BASE);
        Set<String> seen = new HashSet<>();
        String[] lines = source.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] pieces = line.split("\\s+", 2);
            if (pieces.length != 2) throw error(index, "Expected a command and value");
            String command = pieces[0].toLowerCase(Locale.ROOT);
            String value = pieces[1].trim();
            try {
                switch (command) {
                    case "name" -> {
                        unique(seen, command);
                        DeskProfile.validateName(value);
                        name = value;
                    }
                    case "ticker" -> {
                        unique(seen, command);
                        ticker = value.toUpperCase(Locale.ROOT);
                        if (!ticker.matches("[A-Z][A-Z0-9.-]{0,9}"))
                            throw new IllegalArgumentException("Invalid ticker");
                    }
                    case "range" -> { unique(seen, command); range = value.toUpperCase(Locale.ROOT); }
                    case "bars" -> { unique(seen, command); bars = value; }
                    case "qty" -> {
                        unique(seen, command);
                        quantity = Integer.parseInt(value);
                        if (quantity < 1 || quantity > 100)
                            throw new IllegalArgumentException("Quantity must be 1–100 whole shares");
                    }
                    case "input" -> {
                        Matcher match = INPUT.matcher(value);
                        if (!match.matches()) throw new IllegalArgumentException(
                                "Use input alias Custom(name) or input alias Model(name,metric)");
                        String alias = match.group(1).toLowerCase(Locale.ROOT);
                        if (!names.add(alias)) throw new IllegalArgumentException("Duplicate input name: " + alias);
                        String kind = match.group(2).toLowerCase(Locale.ROOT);
                        String sourceName = match.group(3).toLowerCase(Locale.ROOT);
                        String metric = match.group(4);
                        if (kind.equals("custom") && metric != null || kind.equals("model") && metric == null)
                            throw new IllegalArgumentException("Custom needs one name; Model needs name and metric");
                        inputs.add(new Input(alias, kind, sourceName,
                                metric == null ? "" : metric.toLowerCase(Locale.ROOT)));
                        if (inputs.size() > 20) throw new IllegalArgumentException("At most 20 inputs are allowed");
                    }
                    case "let" -> {
                        Matcher match = LET.matcher(value);
                        if (!match.matches()) throw new IllegalArgumentException("Use let name = equation");
                        String alias = match.group(1).toLowerCase(Locale.ROOT);
                        if (names.contains(alias)) throw new IllegalArgumentException("Duplicate variable: " + alias);
                        Expr expression = Expression.parse(match.group(2), names);
                        names.add(alias);
                        assignments.add(new Assignment(alias, expression));
                        if (assignments.size() > 20) throw new IllegalArgumentException("At most 20 equations are allowed");
                    }
                    case "buy" -> { unique(seen, command); buy = Expression.parse(value, names); }
                    case "sell" -> { unique(seen, command); sell = Expression.parse(value, names); }
                    default -> throw new IllegalArgumentException("Unknown command: " + command);
                }
            } catch (IllegalArgumentException error) {
                throw error(index, error.getMessage());
            }
        }
        if (name == null || ticker == null || range == null || bars == null || quantity == 0
                || buy == null && sell == null)
            throw new IllegalArgumentException("Add name, ticker, range, bars, qty, and at least one buy/sell rule.");
        ChartScript.parse("ticker " + ticker + "\nrange " + range + "\nbars " + bars
                + "\nrefresh off\nstudy none\n");
        return new Parsed(name, ticker, range, bars, quantity, List.copyOf(inputs),
                List.copyOf(assignments), buy, sell);
    }

    private static void unique(Set<String> seen, String command) {
        if (!seen.add(command)) throw new IllegalArgumentException("Duplicate " + command);
    }

    private static IllegalArgumentException error(int zeroBased, String message) {
        return new IllegalArgumentException("Line " + (zeroBased + 1) + ": " + message);
    }

    record Input(String alias, String kind, String name, String metric) {}
    record Assignment(String alias, Expr expression) {}
    record Evaluation(Map<String, Double> values, boolean buy, boolean sell) {
        String side() { return buy ? "buy" : sell ? "sell" : "none"; }
    }
    record Parsed(String name, String ticker, String range, String bars, int quantity,
                  List<Input> inputs, List<Assignment> assignments, Expr buyRule, Expr sellRule) {
        Evaluation evaluate(Map<String, Double> observed) {
            Map<String, Double> values = new LinkedHashMap<>();
            observed.forEach((key, value) -> {
                if (value != null && Double.isFinite(value)) values.put(key.toLowerCase(Locale.ROOT), value);
            });
            for (Assignment assignment : assignments) {
                double value = assignment.expression().eval(values);
                if (!Double.isFinite(value)) throw new IllegalArgumentException(
                        "Equation " + assignment.alias() + " produced a non-finite value.");
                values.put(assignment.alias(), value);
            }
            boolean buy = buyRule != null && Expression.booleanValue(buyRule.eval(values));
            boolean sell = sellRule != null && Expression.booleanValue(sellRule.eval(values));
            if (buy && sell) throw new IllegalArgumentException("Buy and sell rules are both true; no order is allowed.");
            return new Evaluation(Map.copyOf(values), buy, sell);
        }
    }

    @FunctionalInterface interface Expr { double eval(Map<String, Double> values); }

    private static final class Expression {
        private final String source;
        private final Set<String> allowed;
        private int cursor;

        private Expression(String source, Set<String> allowed) { this.source = source; this.allowed = allowed; }

        static Expr parse(String source, Set<String> allowed) {
            Expression parser = new Expression(source, Set.copyOf(allowed));
            Expr expression = parser.or();
            parser.space();
            if (parser.cursor != source.length()) throw new IllegalArgumentException(
                    "Unexpected expression text near: " + source.substring(parser.cursor));
            return expression;
        }

        private Expr or() {
            Expr left = and();
            while (word("or")) {
                Expr first = left, second = and();
                left = values -> booleanValue(first.eval(values)) || booleanValue(second.eval(values)) ? 1 : 0;
            }
            return left;
        }

        private Expr and() {
            Expr left = comparison();
            while (word("and")) {
                Expr first = left, second = comparison();
                left = values -> booleanValue(first.eval(values)) && booleanValue(second.eval(values)) ? 1 : 0;
            }
            return left;
        }

        private Expr comparison() {
            Expr left = add();
            String operator = null;
            for (String candidate : List.of(">=", "<=", "==", "!=", ">", "<")) {
                if (take(candidate)) { operator = candidate; break; }
            }
            if (operator == null) return left;
            Expr first = left, second = add();
            String chosen = operator;
            return values -> {
                double a = finite(first.eval(values)), b = finite(second.eval(values));
                return switch (chosen) {
                    case ">=" -> a >= b ? 1 : 0;
                    case "<=" -> a <= b ? 1 : 0;
                    case "==" -> a == b ? 1 : 0;
                    case "!=" -> a != b ? 1 : 0;
                    case ">" -> a > b ? 1 : 0;
                    default -> a < b ? 1 : 0;
                };
            };
        }

        private Expr add() {
            Expr left = multiply();
            while (true) {
                String op = take("+") ? "+" : take("-") ? "-" : null;
                if (op == null) return left;
                Expr first = left, second = multiply();
                left = values -> finite(op.equals("+") ? first.eval(values) + second.eval(values)
                        : first.eval(values) - second.eval(values));
            }
        }

        private Expr multiply() {
            Expr left = unary();
            while (true) {
                String op = take("*") ? "*" : take("/") ? "/" : null;
                if (op == null) return left;
                Expr first = left, second = unary();
                left = values -> finite(op.equals("*") ? first.eval(values) * second.eval(values)
                        : first.eval(values) / second.eval(values));
            }
        }

        private Expr unary() {
            if (take("-")) { Expr inner = unary(); return values -> -inner.eval(values); }
            if (word("not")) { Expr inner = unary(); return values -> booleanValue(inner.eval(values)) ? 0 : 1; }
            return primary();
        }

        private Expr primary() {
            if (take("(")) {
                Expr inner = or();
                if (!take(")")) throw new IllegalArgumentException("Missing closing parenthesis");
                return inner;
            }
            space();
            int start = cursor;
            if (cursor < source.length() && (Character.isDigit(source.charAt(cursor))
                    || source.charAt(cursor) == '.')) {
                while (cursor < source.length() && (Character.isDigit(source.charAt(cursor))
                        || source.charAt(cursor) == '.')) cursor++;
                double number = Double.parseDouble(source.substring(start, cursor));
                if (!Double.isFinite(number)) throw new IllegalArgumentException("Invalid number");
                return values -> number;
            }
            while (cursor < source.length() && (Character.isLetterOrDigit(source.charAt(cursor))
                    || source.charAt(cursor) == '_')) cursor++;
            if (start == cursor) throw new IllegalArgumentException("Expected number or variable");
            String name = source.substring(start, cursor).toLowerCase(Locale.ROOT);
            if (!allowed.contains(name)) throw new IllegalArgumentException("Unknown variable: " + name);
            return values -> {
                Double value = values.get(name);
                if (value == null) throw new IllegalArgumentException("No observed value for " + name);
                return finite(value);
            };
        }

        private boolean take(String token) {
            space();
            if (!source.startsWith(token, cursor)) return false;
            cursor += token.length();
            return true;
        }

        private boolean word(String token) {
            space();
            int end = cursor + token.length();
            if (end > source.length() || !source.regionMatches(true, cursor, token, 0, token.length())
                    || end < source.length() && (Character.isLetterOrDigit(source.charAt(end))
                    || source.charAt(end) == '_')) return false;
            cursor = end;
            return true;
        }

        private void space() { while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) cursor++; }

        private static boolean booleanValue(double value) {
            if (value != 0 && value != 1) throw new IllegalArgumentException(
                    "A buy/sell or logical expression must resolve to true or false (0 or 1).");
            return value == 1;
        }

        private static double finite(double value) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Equation produced a non-finite value.");
            return value;
        }
    }
}
