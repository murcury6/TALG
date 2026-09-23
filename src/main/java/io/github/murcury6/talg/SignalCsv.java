package io.github.murcury6.talg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Strict CSV contract: seven comma-free fields and no quoted values. */
public final class SignalCsv {
    private static final String HEADER = "symbol,as_of_utc,price_usd,expected_return_1d,forecast_annual_volatility,horizon_days,model_version";

    private SignalCsv() {}

    public static List<Signal> read(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !HEADER.equals(lines.getFirst().strip())) {
            throw new IllegalArgumentException("Unexpected signal CSV header");
        }
        List<Signal> result = new ArrayList<>();
        for (int lineNo = 1; lineNo < lines.size(); lineNo++) {
            String line = lines.get(lineNo).strip();
            if (line.isEmpty()) continue;
            String[] cells = line.split(",", -1);
            if (cells.length != 7) {
                throw new IllegalArgumentException("Expected seven fields on line " + (lineNo + 1));
            }
            result.add(new Signal(cells[0], Instant.parse(cells[1]),
                    Double.parseDouble(cells[2]), Double.parseDouble(cells[3]),
                    Double.parseDouble(cells[4]), Integer.parseInt(cells[5]), cells[6]));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Signal CSV has no rows");
        }
        return List.copyOf(result);
    }
}
