package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SignalCsvTest {
    @TempDir Path temporary;

    @Test void acceptsActualModelContractAndRejectsWrongHeader() throws Exception {
        Path valid = temporary.resolve("signals.csv");
        Files.writeString(valid, "symbol,as_of_utc,price_usd,expected_return_1d,"
                + "forecast_annual_volatility,horizon_days,model_version\n"
                + "AAPL,2026-09-22T15:00:00Z,200,0.01,0.22,1,run-17\n");
        assertEquals("run-17", SignalCsv.read(valid).getFirst().modelVersion());
        Path invalid = temporary.resolve("invalid.csv");
        Files.writeString(invalid, "ticker,forecast\nAAPL,0.01\n");
        assertThrows(IllegalArgumentException.class, () -> SignalCsv.read(invalid));
    }
}
