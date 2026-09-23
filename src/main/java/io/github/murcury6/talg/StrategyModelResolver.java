package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/** Reads only validated, recent outputs from the existing Analysis studio model runner. */
final class StrategyModelResolver {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path projectRoot;

    StrategyModelResolver(Path projectRoot) { this.projectRoot = projectRoot.toAbsolutePath().normalize(); }

    Resolved resolve(String model, String metric, String symbol, String feed, Instant now) throws IOException {
        if (!model.matches("[a-z][a-z0-9_]{0,39}") || !metric.matches("[a-z][a-z0-9_]{0,39}"))
            throw new IOException("Invalid model or metric name.");
        Path source = projectRoot.resolve("work/models").resolve(model + ".R");
        if (!Files.isRegularFile(source)) throw new IOException("Model source is missing: " + model);
        Path resultDir = projectRoot.resolve("work/models/results");
        if (!Files.isDirectory(resultDir)) throw new IOException("No model results are saved yet.");
        Path latest = null;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(resultDir, model + "-*.json")) {
            for (Path file : files) if (Files.isRegularFile(file)
                    && (latest == null || file.getFileName().toString()
                    .compareTo(latest.getFileName().toString()) > 0)) latest = file;
        }
        if (latest == null) throw new IOException("Run model " + model + " in Analysis studio first.");
        if (Files.size(latest) > 1_000_000) throw new IOException("Model result is too large.");
        JsonNode result = JSON.readTree(latest.toFile());
        if (result == null || !result.isObject() || !result.path("rows").isArray())
            throw new IOException("Model result has an invalid structure.");
        if (!model.equals(result.path("model").asText()) || !feed.equals(result.path("feed").asText())
                || !"Alpaca stock bars".equals(result.path("input_source").asText()))
            throw new IOException("Model result source, name, or feed does not match this strategy.");
        if (!md5(source).equalsIgnoreCase(result.path("script_md5").asText()))
            throw new IOException("Model code changed since this result; rerun it in Analysis studio.");
        Instant observedThrough = instant(result.path("observed_through_utc").asText(), "model input");
        if (observedThrough.isAfter(now) || Duration.between(observedThrough, now).compareTo(Duration.ofDays(4)) > 0)
            throw new IOException("Model inputs are stale; rerun the model.");
        for (JsonNode row : result.path("rows")) {
            if (!symbol.equals(row.path("symbol").asText()) || !metric.equals(row.path("metric").asText())) continue;
            Instant asOf = instant(row.path("as_of_utc").asText(), "model row");
            if (asOf.isAfter(now) || Duration.between(asOf, now).compareTo(Duration.ofDays(4)) > 0)
                throw new IOException("Model metric is stale; rerun the model.");
            JsonNode value = row.path("value");
            if (!value.isNumber() || !Double.isFinite(value.asDouble()))
                throw new IOException("Model metric is not finite.");
            return new Resolved(value.asDouble(), asOf, latest,
                    row.path("unit").asText(), row.path("horizon").asText());
        }
        throw new IOException("No " + metric + " result for " + symbol + " in model " + model + ".");
    }

    private static Instant instant(String value, String label) throws IOException {
        try { return Instant.parse(value); }
        catch (RuntimeException error) { throw new IOException("Invalid " + label + " timestamp.", error); }
    }

    private static String md5(Path source) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(Files.readAllBytes(source))); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("MD5 is unavailable.", impossible); }
    }

    record Resolved(double value, Instant asOf, Path source, String unit, String horizon) {}
}
