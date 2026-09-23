package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StrategyStoreTest {
    @TempDir Path temporary;

    @Test void savesAndLoadsAnExplicitRuleScript() throws Exception {
        StrategyStore store = new StrategyStore(temporary.resolve("strategies"));
        String source = Files.readString(Path.of("work", "strategies", "Trend Volume.strategy"));
        store.save(source);
        assertEquals(List.of("Trend Volume"), store.names());
        assertEquals(source, store.load("Trend Volume"));
        assertThrows(IllegalArgumentException.class, () -> store.path("../bad"));
    }
}
