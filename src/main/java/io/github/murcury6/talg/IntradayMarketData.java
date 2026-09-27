package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** IEX/SIP data using the connected account's entitlement; no paid feed is requested implicitly. */
final class IntradayMarketData {
    record Quote(double bid, double ask, String time, double bidSize, double askSize) {
        Quote(double bid, double ask, String time) { this(bid, ask, time, Double.NaN, Double.NaN); }
    }
    private final AlpacaSettings settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    IntradayMarketData(AlpacaSettings settings) { this.settings = settings; }
    JsonNode get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("https://data.alpaca.markets" + path)).timeout(Duration.ofSeconds(20))
                .header("APCA-API-KEY-ID", settings.apiKey()).header("APCA-API-SECRET-KEY", settings.apiSecret()).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Alpaca minute-data request returned HTTP " + response.statusCode());
        return PaperTestRunner.JSON.readTree(response.body());
    }
    List<IntradayMomentum.Bar> bars(Instant start, Instant end) throws Exception {
        return bars("SPY", start, end);
    }
    List<IntradayMomentum.Bar> bars(String symbol, Instant start, Instant end) throws Exception {
        return bars(symbol, start, end, false);
    }
    List<IntradayMomentum.Bar> bars(String symbol, Instant start, Instant end, boolean extended) throws Exception {
        if (!symbol.matches("[A-Z]{1,5}")) throw new IllegalArgumentException("Invalid symbol");
        List<IntradayMomentum.Bar> result = new ArrayList<>(); String token = "";
        for (int page = 0; page < 20; page++) {
            JsonNode data = get("/v2/stocks/" + symbol + "/bars?timeframe=1Min&start=" + start + "&end=" + end
                    + "&adjustment=raw&feed=" + settings.feed() + "&sort=asc&limit=10000"
                    + (token.isBlank() ? "" : "&page_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)));
            if (!data.path("bars").isArray()) throw new IOException("Alpaca did not return a minute-bar array");
            for (var bar : data.path("bars")) {
                Instant time = Instant.parse(bar.path("t").asText());
                LocalTime local = time.atZone(PaperTestRunner.EASTERN).toLocalTime();
                if (local.isBefore(extended ? LocalTime.of(4, 0) : LocalTime.of(9, 30))
                        || !local.isBefore(extended ? LocalTime.of(20, 0) : LocalTime.of(16, 0)) || time.plusSeconds(60).isAfter(end)) continue;
                double open = bar.path("o").asDouble(Double.NaN), high = bar.path("h").asDouble(Double.NaN),
                        low = bar.path("l").asDouble(Double.NaN), close = bar.path("c").asDouble(Double.NaN), volume = bar.path("v").asDouble(Double.NaN);
                if (!Double.isFinite(open + high + low + close + volume) || low <= 0 || high < Math.max(open, close)
                        || low > Math.min(open, close) || volume <= 0) throw new IOException("Invalid OHLCV minute bar");
                result.add(new IntradayMomentum.Bar(time.toString(), open, high, low, close, volume, bar.path("vw").asDouble(Double.NaN)));
            }
            token = data.path("next_page_token").asText("");
            if (token.isBlank() || token.equals("null")) return result.stream().sorted(Comparator.comparing(IntradayMomentum.Bar::time)).toList();
        }
        throw new IOException("Minute history exceeded pagination limit; result not used");
    }
    IntradayMomentum.Signal signal(Instant now) throws Exception {
        Instant start = now.atZone(PaperTestRunner.EASTERN).toLocalDate().atTime(9, 30).atZone(PaperTestRunner.EASTERN).toInstant();
        List<IntradayMomentum.Bar> bars = bars(start, now.minusSeconds(5));
        if (!bars.isEmpty() && Duration.between(Instant.parse(bars.getLast().time()).plusSeconds(60), now).getSeconds() > 150)
            throw new IOException("Latest completed minute bar is stale");
        // Sparse IEX intervals are not forward-filled into fictional bars.
        if (bars.size() >= 22 && Duration.between(Instant.parse(bars.get(bars.size() - 22).time()), Instant.parse(bars.getLast().time())).getSeconds() > 25 * 60)
            throw new IOException("Recent IEX minute history has too many gaps for this signal");
        return OpeningRangeStrategy.evaluate(bars);
    }
    Quote quote() throws Exception {
        JsonNode quote = get("/v2/stocks/SPY/quotes/latest?feed=" + settings.feed()).path("quote");
        double bid = quote.path("bp").asDouble(Double.NaN), ask = quote.path("ap").asDouble(Double.NaN);
        Instant at;
        try { at = Instant.parse(quote.path("t").asText()); } catch (Exception error) { throw new IOException("Quote has no valid timestamp"); }
        long age = Duration.between(at, Instant.now()).getSeconds();
        if (!Double.isFinite(bid + ask) || bid <= 0 || ask < bid || age < -5 || age > 30) throw new IOException("Quote is invalid or more than 30 seconds old");
        return new Quote(bid, ask, at.toString());
    }
}
