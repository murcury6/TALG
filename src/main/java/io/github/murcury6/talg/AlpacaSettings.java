package io.github.murcury6.talg;

/** In-memory market-data credentials and refresh preference. */
public record AlpacaSettings(String apiKey, String apiSecret, String feed, int refreshSeconds) {
    public AlpacaSettings {
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            throw new IllegalArgumentException("Alpaca key and secret are required");
        }
        if (!"iex".equals(feed) && !"sip".equals(feed) && !"delayed_sip".equals(feed)) {
            throw new IllegalArgumentException("Choose a supported Alpaca stock feed");
        }
        if (refreshSeconds < 15 || refreshSeconds > 300) {
            throw new IllegalArgumentException("Refresh interval must be between 15 and 300 seconds");
        }
    }

    @Override public String toString() {
        return "AlpacaSettings[apiKey=<redacted>, apiSecret=<redacted>, feed=" + feed
                + ", refreshSeconds=" + refreshSeconds + "]";
    }
}
