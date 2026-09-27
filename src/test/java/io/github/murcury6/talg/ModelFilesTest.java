package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelFilesTest {
    @TempDir Path root;
    static final String NEWS = """
            {"version":1,"encoder":{"dimension":384,"model":"test"},
            "half_lives_seconds":[300,1800],"prior_mass":1,"snapshot_seconds":2,
            "article_export_seconds":60,"batch_size":16,"association_weights":{"provider_symbol":1},
            "ticker_rules":[],"effect_heads":{}}
            """;
    void write(String path, String content) throws Exception {
        Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, content);
    }
    @Test void preservesExactSourceAndKeepsPreviousModel() throws Exception {
        ModelFiles files = new ModelFiles(root);
        String original = PaperTestRunner.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(RapidPaperModel.defaults());
        write("work/strategies/rapid-paper.json", original);
        String changed = original.replace("let expected_edge =", "# custom note\\nlet expected_edge =");
        files.save(false, original, changed);
        assertEquals(changed, files.read(false));
        try (var versions = Files.list(root.resolve("work/models/history"))) {
            assertEquals(original, Files.readString(versions.findFirst().orElseThrow()));
        }
    }
    @Test void refusesConflictsAndActiveSessionWithoutTouchingFiles() throws Exception {
        ModelFiles files = new ModelFiles(root);
        String original = PaperTestRunner.JSON.writeValueAsString(RapidPaperModel.defaults());
        write("work/strategies/rapid-paper.json", original);
        assertThrows(java.io.IOException.class, () -> files.save(false, "stale text", original));
        write("work/rapid-paper/session.json", "{\"phase\":\"WAITING_NEXT\",\"model\":{\"frozen\":true}}");
        String session = Files.readString(root.resolve("work/rapid-paper/session.json"));
        assertThrows(java.io.IOException.class, () -> files.save(false, original, original));
        assertEquals(session, Files.readString(root.resolve("work/rapid-paper/session.json")));
        assertEquals(original, files.read(false));
    }
    @Test void validatesNewsSettingsAndDoesNotLetDescriptiveFormulasPretendToExecute() throws Exception {
        ModelFiles files = new ModelFiles(root); write("work/models/news-tensor.json", NEWS);
        String changed = NEWS.replace("[300,1800]", "[600,3600]");
        files.save(true, NEWS, changed); assertEquals(changed, files.read(true));
        assertThrows(java.io.IOException.class, () -> files.save(true, changed, changed.replace("[600,3600]", "[0,3600]")));
        assertThrows(java.io.IOException.class, () -> files.save(true, changed, changed.replace("\"dimension\":384", "\"dimension\":128")));
        assertThrows(java.io.IOException.class, () -> files.save(true, changed, changed.replace("\"version\":1", "\"decay_expression\":\"fake code\",\"version\":1")));
        assertEquals(changed, files.read(true));
    }
    @Test void weightedProfileKeepsPipelineContractAndValidatesSymbolsAndDecay() throws Exception {
        var value = PaperTestRunner.JSON.createObjectNode();
        value.put("version", 1).put("engine", "symbol_weighted_tensor_v1").put("refresh_mode", "on_demand");
        value.putArray("symbols").add("AAPL");
        var pipeline = value.putObject("pipeline");
        pipeline.put("schema_version", 1).put("batch_size", 16).put("denominator", "count_of_all_related_articles_in_the_current_source_snapshot");
        pipeline.putObject("encoder").put("model", "pinned");
        pipeline.putObject("decay").put("kind", "shock_plus_background").put("shock_fraction", .8)
                .put("shock_half_life_seconds", 300).put("background_half_life_seconds", 7200);
        ModelFiles.validateNews(value, value.deepCopy());
        var changed = value.deepCopy(); changed.withArray("symbols").add("MSFT");
        ModelFiles.validateNews(changed, value);
        changed.withArray("symbols").add("AAPL");
        assertThrows(java.io.IOException.class, () -> ModelFiles.validateNews(changed, value));
        var invalid = value.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("pipeline").path("decay")).put("shock_fraction", -1);
        assertThrows(java.io.IOException.class, () -> ModelFiles.validateNews(invalid, value));
        var contract = value.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)contract.path("pipeline")).put("formula", "a different operation");
        assertThrows(java.io.IOException.class, () -> ModelFiles.validateNews(contract, value));
    }
}
