package io.github.murcury6.talg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.stream.Stream;

/** Runs the validated Python Alpaca daily-bars adapter for a watchlist symbol. */
final class HistoricalBackfillService {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    Result fetchOneYear(String symbol, AlpacaSettings settings) throws IOException, InterruptedException {
        if (symbol == null || !symbol.matches("[A-Z][A-Z0-9.-]{0,9}")) {
            throw new IllegalArgumentException("Invalid stock ticker");
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String feed = "delayed_sip".equals(settings.feed()) ? "iex" : settings.feed();
        Path root = Path.of("").toAbsolutePath().normalize();
        Path directory = root.resolve("data").resolve("market");
        Files.createDirectories(directory);
        String name = symbol + "-" + today.minusYears(1) + "-" + today + "-" + feed + "-"
                + STAMP.format(java.time.Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8)
                + ".csv";
        Path output = directory.resolve(name);
        ProcessBuilder builder = new ProcessBuilder("python", "-m", "talg_py.market_data", symbol,
                today.minusYears(1).toString(), today.plusDays(1).toString(), output.toString());
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("APCA_API_KEY_ID", settings.apiKey());
        builder.environment().put("APCA_API_SECRET_KEY", settings.apiSecret());
        builder.environment().put("APCA_API_DATA_FEED", feed);
        Process process = builder.start();
        byte[] outputText = process.getInputStream().readAllBytes();
        String detail = new String(outputText, 0, Math.min(outputText.length, 4096),
                StandardCharsets.UTF_8).trim();
        int exit = process.waitFor();
        if (exit != 0) {
            Files.deleteIfExists(output);
            throw new IOException("Python Alpaca backfill failed" + (detail.isEmpty() ? ""
                    : ": " + detail.lines().reduce((first, last) -> last).orElse("")));
        }
        if (!Files.isRegularFile(output)) throw new IOException("Backfill did not create a CSV file.");
        long bars;
        try (Stream<String> lines = Files.lines(output, StandardCharsets.UTF_8)) {
            bars = Math.max(0, lines.count() - 1);
        }
        if (bars == 0) {
            Files.deleteIfExists(output);
            throw new IOException("Alpaca returned no historical bars for " + symbol + ".");
        }
        return new Result(output, bars, feed);
    }

    record Result(Path path, long bars, String feed) {}
}
