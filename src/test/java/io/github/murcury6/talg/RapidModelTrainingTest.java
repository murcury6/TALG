package io.github.murcury6.talg;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit read-only data collection/training; never submits a broker order. */
@EnabledIfSystemProperty(named="talg.trainRapid", matches="true")
class RapidModelTrainingTest {
    @Test void trainOnEarlierDatesAndEvaluateLaterDates() throws Exception {
        var saved = new CredentialStore().load().orElseThrow(); assertEquals("paper", saved.mode()); assertEquals("iex", saved.settings().feed());
        var data = new IntradayMarketData(saved.settings()); Path folder = Path.of("work/rapid-paper/research"); Files.createDirectories(folder);
        List<StatisticalReturnModel.Example> examples = new ArrayList<>();
        for (String symbol : RapidPaperRunner.SYMBOLS) {
            Path file = folder.resolve(symbol + "-2026-09-08-to-22-iex.json"); List<IntradayMomentum.Bar> bars;
            if (Files.exists(file)) bars = PaperTestRunner.JSON.readValue(file.toFile(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            else { bars = data.bars(symbol, Instant.parse("2026-09-08T13:30:00Z"), Instant.parse("2026-09-22T20:00:00Z")); PaperTestRunner.JSON.writeValue(file.toFile(), bars); }
            examples.addAll(StatisticalReturnModel.examples(symbol, bars)); System.out.println(symbol + ": " + bars.size() + " historical IEX minute bars");
        }
        var train = examples.stream().filter(e -> e.date().compareTo("2026-09-18") < 0).toList();
        var validation = examples.stream().filter(e -> e.date().compareTo("2026-09-18") >= 0).toList();
        var model = StatisticalReturnModel.fit(train, RapidPaperRunner.SYMBOLS, "2026-09-17").validate(validation);
        Files.createDirectories(Path.of("work/models")); PaperTestRunner.JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("work/models/rapid-return-v1.json").toFile(), model);
        PaperTestRunner.JSON.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("validation-summary.json").toFile(), Map.of("model", model, "validationStarts", "2026-09-18",
                "target", "two-minute close-to-close return", "limitations", "Overlapping forecasts, not independent trades or portfolio P&L. Assumed costs 4bps round-trip plus $0.02/share. IEX only; execution/spread/latency omitted. No guarantee of profit."));
        System.out.println(PaperTestRunner.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(model.validation()));
    }
}
