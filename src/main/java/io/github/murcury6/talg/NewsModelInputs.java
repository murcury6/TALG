package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;

/** Calls the news model's materialized symbol results; contains no scoring formula. */
final class NewsModelInputs {
    record Result(Map<String, Double> values, JsonNode provenance) {}
    private final JsonNode snapshot;
    private final String error;
    private final Instant now;
    NewsModelInputs(Path root, String script, Instant now) {
        this(root, script, now, null);
    }
    NewsModelInputs(Path root, String script, Instant now, String reference) {
        this.now = now;
        JsonNode data = null; String failure = null;
        try {
            boolean needed = StrategyScript.parse(script, java.util.Set.of("previous_close", "predicted_return", "trading_cost", "prediction_rank"))
                    .inputs().stream().anyMatch(input -> input.kind().equals("news"));
            if (needed) {
                var profiles = new ModelProfiles(root);
                Path results = reference == null ? root.resolve("work/news-tensor/symbol-ratings.json") : profiles.revisionFolder(reference).resolve("runtime/symbol-ratings.json");
                data = PaperTestRunner.JSON.readTree(results.toFile());
                if (reference != null && !reference.equals(data.path("profile_revision").asText())) throw new IllegalArgumentException("News profile revision does not match the pinned model");
                String code = (reference == null ? Files.readString(root.resolve("work/models/news-rating.talg")) : profiles.revision(reference).path("contents").path("source").asText()).replace("\r\n", "\n");
                if (code.startsWith("\ufeff")) code = code.substring(1);
                String version = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
                if (!version.equals(data.path("model_version").asText())) throw new IllegalArgumentException("News code changed; Run News before using its ratings");
            }
        } catch (Exception problem) { failure = "News model unavailable: " + problem.getMessage(); }
        snapshot = data; error = failure;
    }
    Result forSymbol(String script, String symbol) {
        var parsed = StrategyScript.parse(script, java.util.Set.of("previous_close", "predicted_return", "trading_cost", "prediction_rank"));
        Map<String, Double> values = new LinkedHashMap<>();
        var provenance = PaperTestRunner.JSON.createObjectNode();
        for (var input : parsed.inputs()) if (input.kind().equals("news")) {
            if (error != null) throw new IllegalArgumentException(error);
            if (snapshot == null) throw new IllegalArgumentException("News model has no symbol results; Run News first");
            double at = snapshot.path("evaluated_at").asDouble(Double.NaN);
            int maxAge = input.metric().isEmpty() ? 120 : Integer.parseInt(input.metric());
            double age = now.toEpochMilli() / 1000.0 - at;
            if (!Double.isFinite(age) || age < -5 || age > maxAge) throw new IllegalArgumentException("News rating for " + symbol + " is stale; refresh News (maximum " + maxAge + " seconds)");
            JsonNode result = snapshot.path("symbols").path(symbol);
            if (result.isMissingNode()) throw new IllegalArgumentException("News model has no result for " + symbol);
            if (!result.path("error").asText().isEmpty()) throw new IllegalArgumentException("News model for " + symbol + ": " + result.path("error").asText());
            JsonNode output = result.path("outputs").path(input.name());
            if (!output.isNumber() || !Double.isFinite(output.doubleValue())) throw new IllegalArgumentException("News(" + input.name() + ") requires a finite numeric output for " + symbol);
            values.put("news." + input.alias(), output.doubleValue());
            provenance.put("symbol", symbol).put("model_version", snapshot.path("model_version").asText()).put("evaluated_at", at);
            provenance.set("result", result);
            if (snapshot.has("profile_revision")) provenance.set("profile_revision", snapshot.get("profile_revision"));
        }
        return new Result(values, provenance);
    }
}
