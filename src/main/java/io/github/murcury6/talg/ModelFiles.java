package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

/** File-backed model editing. Validation does not arm a model or run any strategy. */
final class ModelFiles {
    private final Path root;
    private String newsProfile = "default", tradeProfile = "default";
    void profile(boolean news, String id) { if (news) newsProfile = id; else tradeProfile = id; }
    ModelFiles(Path root) { this.root = root; }
    Path path(boolean news) throws IOException { return new ModelProfiles(root).file(ModelProfiles.kind(news), news ? newsProfile : tradeProfile, "config"); }
    String read(boolean news) throws IOException { return Files.readString(path(news)); }

    void save(boolean news, String expected, String source) throws IOException {
        Path path = path(news);
        String current = Files.readString(path);
        if (!current.equals(expected)) throw new IOException("File changed outside this editor. Reload before saving.");
        JsonNode value = PaperTestRunner.JSON.readTree(source);
        if (value == null || !value.isObject()) throw new IOException("The model must be a JSON object.");
        if (news) validateNews(value, PaperTestRunner.JSON.readTree(current));
        else {
            PaperTestRunner.JSON.readValue(source, RapidPaperModel.class);
            Path session = root.resolve("work/rapid-paper/session.json");
            if (Files.exists(session)) {
                String phase = PaperTestRunner.JSON.readTree(session.toFile()).path("phase").asText();
                if (tradeProfile.equals("default") && !PaperTestRunner.JSON.readTree(session.toFile()).hasNonNull("profileRevision") && !Set.of("IDLE", "COMPLETE").contains(phase))
                    throw new IOException("Paper session is " + phase + ". Stop the session before saving its model.");
            }
        }
        Path history = root.resolve("work/models/history"); Files.createDirectories(history);
        Files.writeString(history.resolve((news ? "news-" : "trade-") + Instant.now().toString().replace(':', '-')
                + "-" + java.util.UUID.randomUUID() + ".json"), current);
        NewsService.replaceFile(path, source.getBytes(StandardCharsets.UTF_8));
    }

    static void validateNews(JsonNode value, JsonNode original) throws IOException {
        if (value.path("engine").asText().equals("symbol_weighted_tensor_v1")) {
            validateWeightedNews(value, original); return;
        }
        if (original.path("engine").asText().equals("symbol_weighted_tensor_v1"))
            throw new IOException("Changing the News engine requires a separate profile.");
        if (value.path("version").asInt() != 1) throw new IOException("Unsupported news model version.");
        if (!value.path("encoder").equals(original.path("encoder")))
            throw new IOException("Encoder changes require a worker restart and re-encoding. Keep the current encoder in this editor.");
        for (String name : new String[]{"decay_expression", "average_expression", "fading_expression", "decayed_average_formula", "primary_tensor"})
            if (!value.path(name).equals(original.path(name)))
                throw new IOException(name + " describes the Python implementation; change the runtime source to change this formula.");
        JsonNode lives = value.path("half_lives_seconds");
        if (!lives.isArray() || lives.isEmpty()) throw new IOException("Add at least one positive half-life.");
        for (JsonNode life : lives) positive(life, "Half-life");
        positive(value.path("prior_mass"), "Prior mass");
        positive(value.path("snapshot_seconds"), "Snapshot interval");
        positive(value.path("article_export_seconds"), "Article export interval");
        if (!value.path("batch_size").isIntegralNumber() || value.path("batch_size").asInt() < 1 || value.path("batch_size").asInt() > 256)
            throw new IOException("Batch size must be an integer from 1 to 256.");
        if (!value.path("association_weights").isObject() || !value.path("ticker_rules").isArray() || !value.path("effect_heads").isObject())
            throw new IOException("Association weights and effect heads must be objects; ticker rules must be an array.");
        for (JsonNode weight : value.path("association_weights")) relevance(weight, "Relevance weight");
        for (JsonNode rule : value.path("ticker_rules")) {
            if (!rule.path("contains_any").isArray() || !rule.path("tickers").isArray()) throw new IOException("Each ticker rule needs contains_any and tickers arrays.");
            for (JsonNode term : rule.path("contains_any")) if (!term.isTextual() || term.asText().isBlank()) throw new IOException("Rule terms must be nonempty text.");
            for (JsonNode ticker : rule.path("tickers")) if (!ticker.isTextual() || !ticker.asText().matches("[A-Z0-9.^-]{1,15}")) throw new IOException("Invalid rule ticker.");
            if (rule.path("contains_any").isEmpty()) throw new IOException("Each rule needs at least one term.");
            relevance(rule.path("weight"), "Rule weight");
        }
        for (JsonNode head : value.path("effect_heads")) {
            if (!head.path("weights").isArray() || head.path("weights").size() != value.path("encoder").path("dimension").asInt())
                throw new IOException("Each effect head needs one weight per encoder dimension.");
            for (JsonNode weight : head.path("weights")) finite(weight, "Effect weight");
            finite(head.path("bias"), "Effect bias");
            if (!head.path("model_version").isTextual() || head.path("model_version").asText().isBlank()) throw new IOException("Each effect head needs a model_version.");
        }
    }
    private static void validateWeightedNews(JsonNode value, JsonNode original) throws IOException {
        if (value.path("version").asInt() != 1 || !value.path("engine").equals(original.path("engine")))
            throw new IOException("Unsupported weighted News profile version or engine.");
        var symbols = value.path("symbols");
        if (!symbols.isArray() || symbols.isEmpty()) throw new IOException("Add at least one News symbol.");
        var seen = new java.util.HashSet<String>();
        for (var symbol : symbols) if (!symbol.isTextual() || !symbol.asText().matches("[A-Z0-9][A-Z0-9.^-]{0,14}") || !seen.add(symbol.asText()))
            throw new IOException("News symbols must be unique uppercase ticker symbols.");
        var pipeline = value.path("pipeline"); var before = original.path("pipeline");
        if (pipeline.path("schema_version").asInt() != 1 || !pipeline.path("denominator").asText().equals("count_of_all_related_articles_in_the_current_source_snapshot"))
            throw new IOException("Unsupported weighted aggregation contract.");
        for (String key : new String[]{"encoder", "representation", "input_template", "formula", "decay_formula", "relatedness", "connection_weight"})
            if (!pipeline.path(key).equals(before.path(key))) throw new IOException(key + " is a pinned pipeline contract; use a separate model revision to change its implementation.");
        var decay = pipeline.path("decay");
        if (!decay.path("kind").asText().equals("shock_plus_background")) throw new IOException("Unsupported decay function.");
        relevance(decay.path("shock_fraction"), "Shock fraction");
        positive(decay.path("shock_half_life_seconds"), "Shock half-life");
        positive(decay.path("background_half_life_seconds"), "Background half-life");
        if (decay.path("shock_half_life_seconds").asDouble() > decay.path("background_half_life_seconds").asDouble())
            throw new IOException("Shock half-life cannot exceed background half-life.");
        if (!pipeline.path("batch_size").isIntegralNumber() || pipeline.path("batch_size").asInt() < 1 || pipeline.path("batch_size").asInt() > 256)
            throw new IOException("Batch size must be an integer from 1 to 256.");
        if (!value.path("refresh_mode").asText().equals("on_demand")) throw new IOException("Use Run to refresh this News profile.");
    }
    private static void positive(JsonNode number, String label) throws IOException {
        finite(number, label); if (number.asDouble() <= 0) throw new IOException(label + " must be positive.");
    }
    private static void nonnegative(JsonNode number, String label) throws IOException {
        finite(number, label); if (number.asDouble() < 0) throw new IOException(label + " cannot be negative.");
    }
    private static void relevance(JsonNode number, String label) throws IOException {
        nonnegative(number, label); if (number.asDouble() > 1) throw new IOException(label + " must be in [0,1].");
    }
    private static void finite(JsonNode number, String label) throws IOException {
        if (!number.isNumber() || !Double.isFinite(number.asDouble())) throw new IOException(label + " must be a finite number.");
    }
}
