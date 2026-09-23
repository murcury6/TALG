package io.github.murcury6.talg;

import java.time.Instant;

/** Last available price and selected daily context from Alpaca's stock snapshot. */
public record MarketSnapshot(String symbol, double price, double previousClose,
                             double dayOpen, double dayHigh, double dayLow, long dayVolume,
                             Instant priceTime, String priceKind, String feed) {
    public MarketSnapshot {
        if (symbol == null || !symbol.matches("[A-Z][A-Z0-9.-]{0,9}")
                || !Double.isFinite(price) || price <= 0 || priceTime == null) {
            throw new IllegalArgumentException("Invalid market snapshot");
        }
    }

    public double changePercent() {
        return Double.isFinite(previousClose) && previousClose > 0
                ? 100.0 * (price / previousClose - 1.0) : Double.NaN;
    }

}
