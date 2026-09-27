package io.github.murcury6.talg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ModelWorkbookTest {
    @TempDir Path root;
    @Test void workbookDoesNotChangeTradingRulesAndInvalidColumnsAreRejected() {
        String code = "name Test\nticker AAPL\nrange 1D\nbars 1Min\nqty 1\nbuy close > 1\n";
        assertTrue(StrategyScript.parse(code + ModelWorkbook.template(false)).evaluate(Map.of("close", 2.0)).buy());
        assertEquals(2, ModelWorkbook.parse(code + ModelWorkbook.template(false)).workbook().path("sheets").size());
        assertThrows(IllegalArgumentException.class, () -> ModelWorkbook.parse(code + "sheet\n{}\nend sheet\n"));
        assertThrows(IllegalArgumentException.class, () -> ModelWorkbook.parse(code + "sheet\n{}"));
        assertThrows(IllegalArgumentException.class, () -> ModelWorkbook.parse(code + ModelWorkbook.template(false).replace("\"path\"", "\"unknown\"")));
    }
    @Test void newsCallsResolveBySymbolAndFailForStaleMissingOrChangedModels() throws Exception {
        Path source = root.resolve("work/models/news-rating.talg"); Files.createDirectories(source.getParent());
        String code = "output score = 1\n"; Files.writeString(source, code);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path results = root.resolve("work/news-tensor/symbol-ratings.json"); Files.createDirectories(results.getParent());
        Files.writeString(results, "{\"model_version\":\"" + hash + "\",\"evaluated_at\":1000,\"symbols\":{\"AAPL\":{\"outputs\":{\"rating\":0.7},\"error\":\"\"},\"MSFT\":{\"outputs\":{\"rating\":0.2},\"error\":\"\"}}}");
        String strategy = "name News test\nticker AAPL\nrange 1D\nbars 1Min\nqty 1\ninput n News(rating, 30)\nbuy n > 0.5\n";
        var inputs = new NewsModelInputs(root, strategy, Instant.ofEpochSecond(1010));
        var parsed = StrategyScript.parse(strategy);
        assertTrue(parsed.evaluate(inputs.forSymbol(strategy, "AAPL").values()).buy());
        assertFalse(parsed.evaluate(inputs.forSymbol(strategy, "MSFT").values()).buy());
        assertEquals(hash, inputs.forSymbol(strategy, "AAPL").provenance().path("model_version").asText());
        assertThrows(IllegalArgumentException.class, () -> inputs.forSymbol(strategy, "NVDA"));
        assertThrows(IllegalArgumentException.class, () -> new NewsModelInputs(root, strategy, Instant.ofEpochSecond(1031)).forSymbol(strategy, "AAPL"));
        Files.writeString(source, "output score = 2\n");
        assertThrows(IllegalArgumentException.class, () -> new NewsModelInputs(root, strategy, Instant.ofEpochSecond(1010)).forSymbol(strategy, "AAPL"));
        assertThrows(IllegalArgumentException.class, () -> StrategyScript.parse(strategy.replace("30)", "0)")));
    }
}
