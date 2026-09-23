package io.github.murcury6.talg;

import java.time.Instant;

/** One-day, point-in-time forecast supplied by the user's statistical model. */
public record Signal(String symbol, Instant asOfUtc, double priceUsd,
                     double expectedReturn, double forecastAnnualVolatility,
                     int horizonDays, String modelVersion) {

    public Signal {
        if (symbol == null || !symbol.matches("[A-Z][A-Z0-9.-]{0,9}")) {
            throw new IllegalArgumentException("Invalid symbol");
        }
        if (asOfUtc == null || modelVersion == null || modelVersion.isBlank()
                || modelVersion.contains(",")) {
            throw new IllegalArgumentException("Timestamp and model version are required");
        }
        if (!Double.isFinite(priceUsd) || priceUsd <= 0
                || !Double.isFinite(expectedReturn)
                || !Double.isFinite(forecastAnnualVolatility)
                || forecastAnnualVolatility <= 0) {
            throw new IllegalArgumentException("Forecast numbers must be finite; price and volatility positive");
        }
        if (horizonDays != 1) {
            throw new IllegalArgumentException("Only one-day forecasts are supported in this prototype");
        }
    }
}
