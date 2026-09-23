package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/** Read-only client for Alpaca's single-stock snapshot endpoint. */
public final class AlpacaMarketDataClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http;

    public AlpacaMarketDataClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build());
    }

    AlpacaMarketDataClient(HttpClient http) {
        this.http = http;
    }

    HttpRequest requestFor(String symbol, AlpacaSettings settings) {
        if (symbol == null || !symbol.matches("[A-Z][A-Z0-9.-]{0,9}")) {
            throw new IllegalArgumentException("Invalid stock symbol");
        }
        URI uri = URI.create("https://data.alpaca.markets/v2/stocks/" + symbol
                + "/snapshot?feed=" + settings.feed());
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(12))
                .header("APCA-API-KEY-ID", settings.apiKey())
                .header("APCA-API-SECRET-KEY", settings.apiSecret())
                .header("Accept", "application/json")
                .GET().build();
    }

    public MarketSnapshot fetch(String symbol, AlpacaSettings settings)
            throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(requestFor(symbol, settings),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Alpaca market data returned HTTP " + response.statusCode());
        }
        return parseSnapshot(symbol, settings.feed(), response.body());
    }

    static MarketSnapshot parseSnapshot(String symbol, String feed, String json) throws IOException {
        JsonNode root = JSON.readTree(json);
        if (root == null || !root.isObject()) {
            throw new IOException("Alpaca snapshot response is not an object");
        }
        JsonNode trade = root.path("latestTrade");
        JsonNode minute = root.path("minuteBar");
        JsonNode day = root.path("dailyBar");
        JsonNode previous = root.path("prevDailyBar");

        JsonNode priced;
        String priceField;
        String kind;
        if (trade.path("p").isNumber() && trade.path("t").isTextual()) {
            priced = trade; priceField = "p"; kind = "LAST TRADE";
        } else if (minute.path("c").isNumber() && minute.path("t").isTextual()) {
            priced = minute; priceField = "c"; kind = "MINUTE BAR CLOSE";
        } else if (day.path("c").isNumber() && day.path("t").isTextual()) {
            priced = day; priceField = "c"; kind = "DAILY BAR CLOSE";
        } else {
            throw new IOException("Alpaca snapshot has no usable price and timestamp");
        }
        double price = priced.path(priceField).asDouble();
        Instant time;
        try {
            time = Instant.parse(priced.path("t").asText());
        } catch (RuntimeException ex) {
            throw new IOException("Alpaca snapshot has an invalid timestamp", ex);
        }
        if (!Double.isFinite(price) || price <= 0) {
            throw new IOException("Alpaca snapshot has an invalid price");
        }
        return new MarketSnapshot(symbol, price,
                numberOrNaN(previous, "c"), numberOrNaN(day, "o"),
                numberOrNaN(day, "h"), numberOrNaN(day, "l"),
                day.path("v").isIntegralNumber() ? day.path("v").asLong() : -1,
                time, kind, feed.toUpperCase());
    }

    private static double numberOrNaN(JsonNode node, String field) {
        return node.path(field).isNumber() ? node.path(field).asDouble() : Double.NaN;
    }
}
