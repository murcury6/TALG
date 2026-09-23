package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeskProfileTest {
    @TempDir Path temporary;

    @Test void savesAndReloadsAWholeDeskWithoutMarketDataOrCredentials() throws Exception {
        ConfigurablePanel.FormState active = new ConfigurablePanel.FormState("Stock chart",
                "Overview", "3M", "AAPL", ChartScript.template("AAPL"), "");
        ConfigurablePanel.FormState draft = new ConfigurablePanel.FormState("Stock chart",
                "Overview", "3M", "AAPL", "ticker AAPL\n# unfinished draft\n", "");
        DeskProfile profile = new DeskProfile(1, "Quant Desk", List.of("AAPL", "MSFT"),
                "MSFT", 815, 420, List.of(new DeskProfile.Panel(900, 680, 740, 510, draft, active)));
        DeskProfileStore store = new DeskProfileStore(temporary.resolve("profiles"));
        store.save(profile);
        store.setActive("Quant Desk");

        assertEquals(List.of("Quant Desk"), store.names());
        assertEquals("Quant Desk", store.activeName());
        assertEquals(profile, store.load("Quant Desk"));
        String source = Files.readString(store.pathFor("Quant Desk"));
        assertTrue(source.contains("unfinished draft"));
        assertFalse(source.contains("apiSecret"));
        assertFalse(source.contains("marketPrice"));
    }

    @Test void rejectsUnsafeOrUnrestorableScriptsWithoutWritingThem() throws Exception {
        DeskProfileStore store = new DeskProfileStore(temporary.resolve("profiles"));
        assertThrows(IllegalArgumentException.class, () -> store.pathFor("../escape"));
        String invalid = DeskProfile.blank("Default").script().replace("\"version\" : 1", "\"version\" : 99");
        assertThrows(IllegalArgumentException.class, () -> DeskProfile.parse(invalid));
        String secretField = DeskProfile.blank("Default").script()
                .replace("\"panels\" : [ ]", "\"apiSecret\" : \"do-not-store\", \"panels\" : [ ]");
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> DeskProfile.parse(secretField));
        ConfigurablePanel.FormState invalidActive = new ConfigurablePanel.FormState("Stock chart",
                "Overview", "3M", "AAPL", "ticker AAPL\n", "");
        DeskProfile profile = new DeskProfile(1, "Invalid", List.of("AAPL"), "AAPL", 0, 0,
                List.of(new DeskProfile.Panel(0, 0, 500, 350, invalidActive, invalidActive)));
        assertThrows(IllegalArgumentException.class, () -> store.save(profile));
        assertFalse(store.exists("Invalid"));
    }

    @Test void exampleScriptBuildsAChartPortfolioAndQuoteDesk() throws Exception {
        DeskProfile example = DeskProfile.parse(Files.readString(
                Path.of("examples", "Research Desk.desk.json")));
        assertEquals("Research Desk", example.name());
        assertEquals(List.of("Stock chart", "Portfolio", "Market quote"),
                example.panels().stream().map(panel -> panel.active().type()).toList());
        assertEquals("Stock chart", example.panels().getFirst().toWorkspaceState().panel().draft().type());
        assertEquals("MSFT", ChartScript.parse(example.panels().getFirst().active().chartScript())
                .comparisons());
    }

    @Test void quantResearchProfileReferencesRealCustomIndicatorScripts() throws Exception {
        DeskProfile profile = DeskProfile.parse(Files.readString(
                Path.of("work", "profiles", "Quant Research.desk.json")));
        assertEquals(5, profile.panels().size());
        assertEquals(List.of("AAPL", "MSFT", "NVDA", "SPY"), profile.watchlist());
        assertTrue(Files.isRegularFile(Path.of("work", "indicators", "ema_34.R")));
        assertTrue(Files.isRegularFile(Path.of("work", "indicators", "volume_ratio_20.R")));
        assertTrue(Files.isRegularFile(Path.of("work", "indicators", "price_zscore_20.R")));
    }
}
