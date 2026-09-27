package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelProfilesTest {
    @TempDir Path root;
    ModelProfiles setup() throws Exception {
        Files.createDirectories(root.resolve("work/models")); Files.createDirectories(root.resolve("work/strategies"));
        Files.writeString(root.resolve("work/models/news-rating.talg"), "output score = 1\n");
        Files.writeString(root.resolve("work/models/news-tensor.json"), ModelFilesTest.NEWS);
        Files.writeString(root.resolve("work/models/stock-selection.talg"), "param candidates = \"all_available\"\noutput include = True\n");
        Files.writeString(root.resolve("work/strategies/rapid-paper.json"), PaperTestRunner.JSON.writeValueAsString(RapidPaperModel.defaults()));
        var store = new ModelProfiles(root); store.initialize(); return store;
    }
    @Test void independentProfilesFreezeTransitiveDependenciesAndExactInputs() throws Exception {
        var store = setup(); String first = store.snapshot("news", "default");
        String alternate = store.cloneProfile("news", "default", "Breaking news");
        Files.writeString(store.file("news", alternate, "source"), "output score = 2\n");
        String second = store.snapshot("news", alternate);
        assertNotEquals(first, second);
        store.bind("stocks", "default", Map.of("news", first)); String stocks = store.snapshot("stocks", "default");
        store.bind("trade", "default", Map.of("news", second, "stocks", stocks)); String trade = store.snapshot("trade", "default");
        store.select("news", "default"); Files.writeString(store.file("news", alternate, "source"), "output score = 9\n");
        assertEquals(second, store.revision(trade).path("dependencies").path("news").asText());
        assertEquals("output score = 2\n", store.revision(second).path("contents").path("source").asText());
        assertEquals(first, store.revision(stocks).path("dependencies").path("news").asText());
        assertEquals(trade, store.snapshot("trade", "default"));
        assertThrows(java.io.IOException.class, () -> store.bind("news", "default", Map.of("trade", trade)));
        assertThrows(java.io.IOException.class, () -> store.bind("trade", "default", Map.of("news", stocks)));
        assertThrows(java.io.IOException.class, () -> store.revisionFolder("../escape"));
        Files.writeString(store.revisionFolder(second).resolve("manifest.json"), "{}");
        assertThrows(java.io.IOException.class, () -> store.revision(second));
    }
    @Test void defaultLinksAreCapturedOnceAndOtherHeadsArePreserved() throws Exception {
        var store = setup(); String trade = store.snapshot("trade", "default");
        String stocks = store.revision(trade).path("dependencies").path("stocks").asText();
        assertEquals(store.revision(stocks).path("dependencies"), store.dependencies("stocks", "default"));
        String news = store.newsReference("trade", "default");
        Files.writeString(store.file("news", "default", "source"), "output score = 7");
        assertNotEquals(news, store.snapshot("news", "default")); assertEquals(news, store.newsReference("trade", "default"));
    }
    @Test void pinnedNewsAdapterRejectsCrossProfileResultsAndIgnoresCurrentEditor() throws Exception {
        var store = setup(); String ref = store.snapshot("news", "default");
        Path runtime = store.revisionFolder(ref).resolve("runtime"); Files.createDirectories(runtime);
        String hash = ModelProfiles.hash("output score = 1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var data = PaperTestRunner.JSON.createObjectNode().put("profile_revision", ref).put("model_version", hash).put("evaluated_at", 1000);
        data.putObject("symbols").putObject("AAPL").putObject("outputs").put("rating", 4);
        PaperTestRunner.JSON.writeValue(runtime.resolve("symbol-ratings.json").toFile(), data);
        Files.writeString(store.file("news", "default", "source"), "output score = 99");
        String script = "name Pinned\nticker AAPL\nrange 1D\nbars 1Min\nqty 1\ninput news_score News(rating)\nbuy news_score > 0\nsell news_score < 0\n";
        assertEquals(4d, new NewsModelInputs(root, script, Instant.ofEpochSecond(1001), ref).forSymbol(script, "AAPL").values().get("news.news_score"));
        data.put("profile_revision", "wrong"); PaperTestRunner.JSON.writeValue(runtime.resolve("symbol-ratings.json").toFile(), data);
        assertThrows(IllegalArgumentException.class, () -> new NewsModelInputs(root, script, Instant.ofEpochSecond(1001), ref).forSymbol(script, "AAPL"));
    }
    @Test void pickerDoesNotDiscardDraftAndDoesNotSwitchOtherKinds() throws Exception {
        var store = setup(); String clone = store.cloneProfile("news", "default", "Long horizon");
        SwingUtilities.invokeAndWait(() -> {
            boolean[] dirty = {true}; var changes = new ArrayList<String>(); var notices = new ArrayList<String>();
            var picker = new ModelProfileControl(root, "news", () -> dirty[0], changes::add, notices::add);
            picker.setSelectedIndex(1); assertEquals("default", picker.id()); assertTrue(changes.isEmpty());
            dirty[0] = false; picker.setSelectedIndex(1); assertEquals(clone, picker.id()); assertEquals(List.of(clone), changes);
        });
        assertEquals("default", store.selected("trade")); assertEquals(clone, store.selected("news"));
    }
}
