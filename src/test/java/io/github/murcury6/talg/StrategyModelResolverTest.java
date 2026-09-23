package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StrategyModelResolverTest {
    @TempDir Path project;

    @Test void readsRecentMatchingModelOutputAndRejectsChangedCode() throws Exception {
        Path source = project.resolve("work/models/alpha_model.R");
        Path results = project.resolve("work/models/results");
        Files.createDirectories(results);
        Files.writeString(source, "talg_model <- function(series) stop('test')\n");
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                .digest(Files.readAllBytes(source)));
        Instant now = Instant.now();
        String payload = """
                {"model":"alpha_model","script_md5":"%s","input_source":"Alpaca stock bars",
                 "feed":"iex","observed_through_utc":"%s","rows":[
                 {"symbol":"AAPL","as_of_utc":"%s","metric":"expected_return",
                  "value":0.012,"unit":"fraction","horizon":"1D"}]}
                """.formatted(checksum, now.minusSeconds(3600), now.minusSeconds(3600));
        Files.writeString(results.resolve("alpha_model-123.json"), payload);
        StrategyModelResolver resolver = new StrategyModelResolver(project);
        assertEquals(0.012, resolver.resolve("alpha_model", "expected_return", "AAPL", "iex", now).value());
        assertThrows(Exception.class,
                () -> resolver.resolve("alpha_model", "expected_return", "AAPL", "sip", now));
        Files.writeString(source, "changed code\n");
        assertThrows(Exception.class,
                () -> resolver.resolve("alpha_model", "expected_return", "AAPL", "iex", now));
    }
}
