package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Fetches real bars with keys, then evaluates local indicators without key environment variables. */
final class StrategyDataService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path projectRoot;

    StrategyDataService(Path projectRoot) { this.projectRoot = projectRoot.toAbsolutePath().normalize(); }

    Result evaluate(StrategyScript.Parsed strategy, AlpacaSettings settings, String accountMode)
            throws IOException, InterruptedException {
        Path bars = Files.createTempFile("talg-strategy-bars-", ".rds");
        Path input = Files.createTempFile("talg-strategy-inputs-", ".json");
        Path result = Files.createTempFile("talg-strategy-values-", ".json");
        try {
            List<Map<String, String>> custom = strategy.inputs().stream()
                    .filter(item -> item.kind().equals("custom"))
                    .map(item -> Map.of("alias", item.alias(), "name", item.name())).toList();
            JSON.writeValue(input.toFile(), custom);
            ProcessBuilder fetch = RRuntime.builder(projectRoot, "r/fetch_display_data.R",
                    bars.toString(), "Stock lab", strategy.range(), strategy.ticker(), strategy.bars(), "");
            fetch.directory(projectRoot.toFile());
            AlpacaProcessEnvironment.supply(fetch, settings, accountMode);
            run(fetch, "market-data fetch", settings);

            ProcessBuilder calculate = RRuntime.builder(projectRoot, "r/evaluate_strategy_data.R",
                    bars.toString(), input.toString(), result.toString());
            calculate.directory(projectRoot.toFile());
            AlpacaProcessEnvironment.scrub(calculate);
            run(calculate, "indicator evaluation", settings);

            JsonNode payload = JSON.readTree(result.toFile());
            if (payload == null || !strategy.ticker().equals(payload.path("symbol").asText())
                    || !payload.path("values").isObject() || !settings.feed().equals(payload.path("feed").asText()))
                throw new IOException("Strategy data source did not match the requested stock and feed.");
            Instant asOf;
            try { asOf = Instant.parse(payload.path("as_of_utc").asText()); }
            catch (RuntimeException error) { throw new IOException("Strategy data has no valid UTC timestamp.", error); }
            Duration age = Duration.between(asOf, Instant.now());
            Duration limit = strategy.bars().equals("1Day") || strategy.bars().equals("1Week")
                    ? Duration.ofDays(4) : Duration.ofHours(2);
            if (age.isNegative() || age.compareTo(limit) > 0)
                throw new IOException("Observed strategy bars are stale for " + strategy.bars() + ".");
            Map<String, Double> values = new LinkedHashMap<>();
            payload.path("values").fields().forEachRemaining(entry -> {
                if (entry.getValue().isNumber() && Double.isFinite(entry.getValue().asDouble()))
                    values.put(entry.getKey(), entry.getValue().asDouble());
            });
            List<String> provenance = new ArrayList<>();
            provenance.add("Alpaca " + settings.feed() + " " + strategy.bars() + " bars through " + asOf);
            StrategyModelResolver models = new StrategyModelResolver(projectRoot);
            for (StrategyScript.Input item : strategy.inputs()) if (item.kind().equals("model")) {
                StrategyModelResolver.Resolved model = models.resolve(item.name(), item.metric(),
                        strategy.ticker(), settings.feed(), Instant.now());
                values.put(item.alias(), model.value());
                provenance.add(item.alias() + " = " + item.name() + "/" + item.metric()
                        + " (" + model.unit() + ", horizon " + model.horizon() + ", "
                        + model.asOf() + ")");
            }
            return new Result(strategy.evaluate(values), asOf, List.copyOf(provenance));
        } finally {
            Files.deleteIfExists(bars);
            Files.deleteIfExists(input);
            Files.deleteIfExists(result);
        }
    }

    private static void run(ProcessBuilder builder, String stage, AlpacaSettings settings)
            throws IOException, InterruptedException {
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader stream = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = stream.readLine()) != null) {
                    synchronized (output) {
                        if (output.length() < 4000) output.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) { /* Exit status determines failure. */ }
        }, "talg-strategy-r-output");
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("R " + stage + " exceeded 180 seconds.");
        }
        reader.join(2000);
        if (process.exitValue() != 0) {
            String safe;
            synchronized (output) { safe = output.toString(); }
            safe = safe.replace(settings.apiKey(), "<redacted>")
                    .replace(settings.apiSecret(), "<redacted>").trim();
            throw new IOException("R " + stage + " failed: " + (safe.isBlank() ? "unknown error" : safe));
        }
    }

    record Result(StrategyScript.Evaluation evaluation, Instant asOf, List<String> provenance) {}
}
