package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AlpacaOrderClientTest {
    @Test void catalogueIsReadOnlyAndIncludesAllTradableUsEquities() throws Exception {
        var reader = new AlpacaOrderClient(request -> {
            assertEquals("GET",request.method());
            assertEquals("https://paper-api.alpaca.markets/v2/assets?status=active&asset_class=us_equity",request.uri().toString());
            return new AlpacaOrderClient.Response(200,"""
                [{"symbol":"AAPL","class":"us_equity","status":"active","tradable":true},
                 {"symbol":"BRK.B","class":"us_equity","status":"active","tradable":true},
                 {"symbol":"AAPL","class":"us_equity","status":"active","tradable":true},
                 {"symbol":"OLD","class":"us_equity","status":"inactive","tradable":true},
                 {"symbol":"LOCK","class":"us_equity","status":"active","tradable":false},
                 {"symbol":"BTC/USD","class":"crypto","status":"active","tradable":true}]
                """);
        },null);
        var catalogue = reader.availableAssets(credentials,"paper");
        assertEquals(2,catalogue.path("assets").size());
        assertEquals("BRK.B",catalogue.path("assets").get(1).path("symbol").asText());
        assertEquals("active_tradable_us_equity",catalogue.path("scope").asText());
        var invalid = new AlpacaOrderClient(request -> new AlpacaOrderClient.Response(200,"{}"),null);
        assertThrows(IOException.class, () -> invalid.availableAssets(credentials,"paper"));
    }
    @Test void loadsActualPositionsWithDecimalQuantitiesAndPercentConversion() throws Exception {
        var reader = new AlpacaOrderClient(request -> {
            assertEquals("GET", request.method());
            assertEquals("https://paper-api.alpaca.markets/v2/positions", request.uri().toString());
            return new AlpacaOrderClient.Response(200, """
                    [{"symbol":"AAPL","side":"long","qty":"1.5","avg_entry_price":"100",
                    "current_price":"110","market_value":"165","unrealized_pl":"15","unrealized_plpc":"0.1"}]
                    """);
        }, (symbol, settings) -> { throw new AssertionError("Positions do not need a quote request"); });
        var position = reader.positions(credentials, "paper").getFirst();
        assertEquals(new BigDecimal("1.5"), position.quantity());
        assertEquals(0, new BigDecimal("10").compareTo(position.unrealizedPercent()));
        var invalid = new AlpacaOrderClient(request -> new AlpacaOrderClient.Response(200, "{}"), null);
        assertThrows(IOException.class, () -> invalid.positions(credentials, "paper"));
    }
    private final AlpacaSettings credentials = new AlpacaSettings("test-key", "test-secret", "iex", 30);
    private final AlpacaOrderClient client = new AlpacaOrderClient();

    @Test void fixesOrderSubmissionToSelectedPaperOrLiveEndpoint() throws Exception {
        AlpacaOrderClient.Intent intent = new AlpacaOrderClient.Intent("AAPL", "buy", 1, 250);
        HttpRequest paper = client.orderRequest(intent, new BigDecimal("101.25"),
                "talg-test", credentials, "paper");
        HttpRequest live = client.orderRequest(intent, new BigDecimal("101.25"),
                "talg-test", credentials, "live");
        assertEquals("https://paper-api.alpaca.markets/v2/orders", paper.uri().toString());
        assertEquals("https://api.alpaca.markets/v2/orders", live.uri().toString());
        assertEquals("POST", paper.method());
        assertEquals("test-key", live.headers().firstValue("APCA-API-KEY-ID").orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> client.orderRequest(intent,
                new BigDecimal("101.25"), "talg-test", credentials, "unknown"));
    }

    @Test void rejectsLargeOrInvalidTicketsBeforeNetworkUse() {
        assertThrows(IllegalArgumentException.class,
                () -> new AlpacaOrderClient.Intent("AAPL", "buy", 101, 2500));
        assertThrows(IllegalArgumentException.class,
                () -> new AlpacaOrderClient.Intent("AAPL", "buy", 1, 2501));
        assertThrows(IllegalArgumentException.class,
                () -> new AlpacaOrderClient.Intent("AAPL/../orders", "buy", 1, 250));
        assertThrows(IllegalArgumentException.class,
                () -> new AlpacaOrderClient.Intent("AAPL", "short", 1, 250));
    }

    @Test void preflightThenSubmitsOnlyToConfirmedLiveAccount() throws Exception {
        List<HttpRequest> sent = new ArrayList<>();
        AlpacaOrderClient.Transport fake = request -> {
            sent.add(request);
            String path = request.uri().getPath();
            if (path.equals("/v2/clock")) return new AlpacaOrderClient.Response(200, "{\"is_open\":true}");
            if (path.equals("/v2/account")) return new AlpacaOrderClient.Response(200,
                    "{\"status\":\"ACTIVE\",\"trading_blocked\":false,\"buying_power\":\"10000\"}");
            if (path.equals("/v2/orders") && request.method().equals("POST"))
                return new AlpacaOrderClient.Response(200,
                        "{\"id\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"status\":\"accepted\"}");
            throw new IOException("Unexpected request");
        };
        AlpacaOrderClient.QuoteSource quotes = (symbol, ignored) ->
                new MarketSnapshot(symbol, 100, 99, 100, 101, 99, 1000,
                        Instant.now(), "LAST TRADE", "IEX");
        AlpacaOrderClient underTest = new AlpacaOrderClient(fake, quotes);
        AlpacaOrderClient.Intent intent = new AlpacaOrderClient.Intent("AAPL", "buy", 2, 250);
        AlpacaOrderClient.Preview preview = underTest.preview(intent, credentials, "live");
        assertEquals(new BigDecimal("100.50"), preview.limitPrice());
        assertEquals(new BigDecimal("201.00"), preview.maxValue());
        AlpacaOrderClient.Submitted submitted = underTest.submit(preview, credentials, "live");
        assertEquals("accepted", submitted.status());
        assertEquals(1, sent.stream().filter(request -> request.method().equals("POST")).count());
        assertTrue(sent.stream().allMatch(request -> request.uri().getHost().equals("api.alpaca.markets")));
    }

    @Test void closedMarketFailsBeforeAnOrderRequest() {
        List<HttpRequest> sent = new ArrayList<>();
        AlpacaOrderClient underTest = new AlpacaOrderClient(request -> {
            sent.add(request);
            return new AlpacaOrderClient.Response(200, "{\"is_open\":false}");
        }, (symbol, ignored) -> { throw new AssertionError("Quote fetch must not occur while closed"); });
        assertThrows(IOException.class, () -> underTest.preview(
                new AlpacaOrderClient.Intent("AAPL", "buy", 1, 250), credentials, "paper"));
        assertEquals(1, sent.size());
        assertEquals("GET", sent.getFirst().method());
    }

    @Test void statusLookupUsesTheSelectedAccountEndpoint() throws Exception {
        List<HttpRequest> sent = new ArrayList<>();
        AlpacaOrderClient underTest = new AlpacaOrderClient(request -> {
            sent.add(request);
            return new AlpacaOrderClient.Response(200, "{\"status\":\"filled\"}");
        }, (symbol, ignored) -> { throw new AssertionError("No quote required for status"); });
        UUID id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        assertEquals("filled", underTest.status(id, credentials, "paper"));
        assertEquals("filled", underTest.status(id, credentials, "live"));
        assertEquals("paper-api.alpaca.markets", sent.get(0).uri().getHost());
        assertEquals("api.alpaca.markets", sent.get(1).uri().getHost());
    }
}
