package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Tests one saved indicator against fetched, observed Alpaca daily bars. */
final class IndicatorTestService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path projectRoot;

    IndicatorTestService(Path projectRoot) { this.projectRoot = projectRoot.toAbsolutePath().normalize(); }

    JsonNode test(String name, String symbol, AlpacaSettings settings, String mode) throws Exception {
        IndicatorRepository.name(name);
        if (symbol == null || !symbol.matches("[A-Z][A-Z0-9.-]{0,9}"))
            throw new IllegalArgumentException("Enter a stock ticker to test this indicator.");
        if (settings == null) throw new IllegalArgumentException("Connect Alpaca in Settings before testing.");
        Path observed = Files.createTempFile("talg-indicator-bars-", ".rds");
        Path result = Files.createTempFile("talg-indicator-test-", ".json");
        try {
            ProcessBuilder fetch = RRuntime.builder(projectRoot, "r/fetch_display_data.R",
                    observed.toString(), "Stock lab", "1Y", symbol, "1Day", "");
            fetch.directory(projectRoot.toFile());
            AlpacaProcessEnvironment.supply(fetch, settings, mode);
            RScriptFiles.run(fetch, 180, settings);

            ProcessBuilder calculate = RRuntime.builder(projectRoot, "r/test_user_indicator.R",
                    name, observed.toString(), result.toString());
            calculate.directory(projectRoot.toFile());
            AlpacaProcessEnvironment.scrub(calculate);
            RScriptFiles.run(calculate, 180, settings);
            JsonNode payload = JSON.readTree(result.toFile());
            if (payload == null || !symbol.equals(payload.path("symbol").asText())
                    || !settings.feed().equals(payload.path("feed").asText())
                    || !payload.path("preview").isArray()
                    || !payload.path("finite_count").canConvertToInt())
                throw new IOException("Indicator preview did not match the requested stock and feed.");
            return payload;
        } finally {
            Files.deleteIfExists(observed);
            Files.deleteIfExists(result);
        }
    }
}
