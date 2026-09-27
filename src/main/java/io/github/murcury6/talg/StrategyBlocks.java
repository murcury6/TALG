package io.github.murcury6.talg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, inspectable building blocks for the declarative strategy language. */
final class StrategyBlocks {
    private static final List<String> OBSERVED = List.of("close", "open", "high", "low", "volume",
            "vwap", "trades");
    private static final Set<String> OPERATORS = Set.of(">", ">=", "<", "<=", "==", "!=");
    private static final Set<String> MATH = Set.of("+", "-", "*", "/", "% difference");
    private static final Pattern NUMBER = Pattern.compile("-?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)");

    private StrategyBlocks() {}

    static List<String> signals(String source) {
        StrategyScript.Parsed parsed = StrategyScript.parse(source);
        List<String> result = new ArrayList<>(OBSERVED);
        parsed.inputs().forEach(input -> result.add(input.alias()));
        parsed.assignments().forEach(assignment -> result.add(assignment.alias()));
        return List.copyOf(result);
    }

    static String addCondition(String source, String rawSide, String rawLeft, String rawOperator,
                               String rawRight, String rawJoin) {
        List<String> signals = signals(source);
        String side = rawSide.trim().toLowerCase(Locale.ROOT);
        String left = rawLeft.trim().toLowerCase(Locale.ROOT);
        String operator = rawOperator.trim();
        String right = rawRight.trim().toLowerCase(Locale.ROOT);
        String join = rawJoin.trim().toLowerCase(Locale.ROOT);
        if (!side.equals("buy") && !side.equals("sell"))
            throw new IllegalArgumentException("Choose Buy or Sell.");
        if (!signals.contains(left))
            throw new IllegalArgumentException("Choose an available signal on the left.");
        if (!OPERATORS.contains(operator))
            throw new IllegalArgumentException("Choose a comparison operator.");
        if (!signals.contains(right) && !NUMBER.matcher(right).matches())
            throw new IllegalArgumentException("Compare with an available signal or a finite number.");
        if (!join.equals("and") && !join.equals("or"))
            throw new IllegalArgumentException("Choose AND or OR to connect conditions.");
        String condition = left + " " + operator + " " + right;
        Pattern command = Pattern.compile("(?m)^(" + side + ")\\s+([^\\r\\n]+)$");
        Matcher match = command.matcher(source);
        String changed;
        if (match.find()) {
            String combined = side + " (" + match.group(2).trim() + ") " + join + " (" + condition + ")";
            changed = source.substring(0, match.start()) + combined + source.substring(match.end());
        } else {
            Matcher quantity = Pattern.compile("(?m)^qty\\s+").matcher(source);
            int position = quantity.find() ? quantity.start() : source.length();
            String prefix = source.substring(0, position);
            if (!prefix.isEmpty() && !prefix.endsWith("\n")) prefix += "\n";
            changed = prefix + side + " " + condition + "\n" + source.substring(position);
        }
        StrategyScript.parse(changed);
        return changed;
    }

    static String addEquation(String source, String rawName, String rawLeft, String rawOperation,
                              String rawRight) {
        List<String> signals = signals(source);
        String name = rawName.trim().toLowerCase(Locale.ROOT);
        String left = rawLeft.trim().toLowerCase(Locale.ROOT);
        String operation = rawOperation.trim();
        String right = rawRight.trim().toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z][a-z0-9_]{0,39}") || signals.contains(name))
            throw new IllegalArgumentException("Give the new equation a unique name using letters, numbers, or _.");
        if (!signals.contains(left))
            throw new IllegalArgumentException("Choose an available signal on the left.");
        if (!MATH.contains(operation))
            throw new IllegalArgumentException("Choose an equation operation.");
        if (!signals.contains(right) && !NUMBER.matcher(right).matches())
            throw new IllegalArgumentException("The other value must be a signal or finite number.");
        if ((operation.equals("/") || operation.equals("% difference")) && right.equals("0"))
            throw new IllegalArgumentException("The divisor cannot be zero.");
        String expression = operation.equals("% difference")
                ? "(" + left + " / " + right + " - 1) * 100"
                : left + " " + operation + " " + right;
        Matcher next = Pattern.compile("(?m)^\\s*(?:buy|sell|qty)\\s+").matcher(source);
        int position = next.find() ? next.start() : source.length();
        String prefix = source.substring(0, position);
        if (!prefix.isEmpty() && !prefix.endsWith("\n")) prefix += "\n";
        String changed = prefix + "let " + name + " = " + expression + "\n" + source.substring(position);
        StrategyScript.parse(changed);
        return changed;
    }
}
