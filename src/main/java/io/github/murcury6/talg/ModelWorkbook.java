package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Model data bindings only. The app owns all presentation; legacy hints are ignored. */
final class ModelWorkbook {
    private static final Pattern BLOCK = Pattern.compile("(?ms)^sheet\\h*\\R(.*?)^end sheet\\h*(?:\\R|$)");
    record Source(String executable, JsonNode workbook) {}
    static Source parse(String source) {
        var match = BLOCK.matcher(source);
        if (!match.find()) {
            if (source.lines().anyMatch(line -> line.strip().equals("sheet") || line.strip().equals("end sheet")))
                throw new IllegalArgumentException("Workbook needs sheet and end sheet lines");
            return new Source(source, null);
        }
        try {
            JsonNode workbook = PaperTestRunner.JSON.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(match.group(1));
            workbook = dataSchema(workbook);
            validate(workbook);
            String executable = source.substring(0, match.start()) + match.group().replaceAll("[^\\r\\n]", " ") + source.substring(match.end());
            if (match.find()) throw new IllegalArgumentException("Use one workbook block with a sheets array");
            if (executable.lines().anyMatch(line -> line.strip().equals("sheet") || line.strip().equals("end sheet")))
                throw new IllegalArgumentException("Unexpected workbook delimiter");
            return new Source(executable, workbook);
        } catch (java.io.IOException error) { throw new IllegalArgumentException("Workbook JSON: " + error.getMessage(), error); }
    }
    static JsonNode dataSchema(JsonNode workbook) {
        if (workbook == null) return null;
        JsonNode result = workbook.deepCopy();
        for (JsonNode sheet : result.path("sheets")) if (sheet.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) sheet).remove(java.util.List.of("sort", "rowHeight", "details"));
            for (JsonNode column : sheet.path("columns")) if (column.isObject())
                ((com.fasterxml.jackson.databind.node.ObjectNode) column).remove(java.util.List.of("label", "format", "precision", "width", "align", "missing"));
        }
        return result;
    }
    static void validate(JsonNode workbook) {
        workbook = dataSchema(workbook);
        require(workbook != null && workbook.isObject(), "Workbook must be an object");
        keys(workbook, Set.of("sheets"));
        JsonNode sheets = workbook.path("sheets");
        require(sheets.isArray() && !sheets.isEmpty() && sheets.size() <= 16, "Workbook needs 1–16 sheets");
        Set<String> names = new HashSet<>();
        for (JsonNode sheet : sheets) {
            keys(sheet, Set.of("name", "rows", "columns"));
            require(sheet.path("name").isTextual() && !sheet.path("name").asText().isBlank() && names.add(sheet.path("name").asText()), "Sheet names must be nonempty and unique");
            pointer(sheet, "rows");
            JsonNode columns = sheet.path("columns");
            require(columns.isArray() && !columns.isEmpty() && columns.size() <= 128, "Each sheet needs 1–128 columns");
            Set<String> ids = new HashSet<>();
            for (JsonNode column : columns) {
                keys(column, Set.of("id", "path", "source", "type"));
                require(column.path("id").isTextual() && !column.path("id").asText().isBlank() && ids.add(column.path("id").asText()), "Column ids must be nonempty and unique");
                require(column.has("path"), "Each column needs a JSON pointer path"); pointer(column, "path");
                choice(column, "source", "row", "record"); choice(column, "type", "auto", "text", "number", "boolean", "json");
            }
        }
    }
    private static void keys(JsonNode node, Set<String> allowed) {
        require(node.isObject(), "Workbook entries must be objects");
        node.fieldNames().forEachRemaining(key -> require(allowed.contains(key), "Unsupported workbook field: " + key));
    }
    private static void pointer(JsonNode node, String key) {
        if (node.has(key)) require(node.path(key).isTextual() && node.path(key).asText().matches("(?:/(?:[^~]|~[01])*)?"), key + " must be a JSON pointer, e.g. /outputs/score");
    }
    private static void choice(JsonNode node, String key, String... values) {
        if (node.has(key)) require(Set.of(values).contains(node.path(key).asText()), "Unsupported " + key + ": " + node.path(key));
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    static String template(boolean news) {
        try (var input = ModelWorkbook.class.getResourceAsStream(news ? "/news-workbook.json" : "/trade-workbook.json")) {
            return "\nsheet\n" + new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip() + "\nend sheet\n";
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
}
