package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AlpacaMarketDataClientTest {
    private static final String SNAPSHOT = """
            {
              "symbol": "AAPL",
              "latestTrade": {"t": "2026-09-22T19:42:00.123Z", "p": 185.42},
              "dailyBar": {"t": "2026-09-22T04:00:00Z", "o": 184.10,
                           "h": 187.36, "l": 182.71, "c": 185.21, "v": 2476100},
              "prevDailyBar": {"t": "2026-09-21T04:00:00Z", "c": 183.90}
            }
            """;

    @Test void parsesTradeAndDayContextWithTimestamp() throws Exception {
        MarketSnapshot snapshot = AlpacaMarketDataClient.parseSnapshot("AAPL", "iex", SNAPSHOT);
        assertEquals(185.42, snapshot.price());
        assertEquals(183.90, snapshot.previousClose());
        assertEquals(2_476_100, snapshot.dayVolume());
        assertEquals(Instant.parse("2026-09-22T19:42:00.123Z"), snapshot.priceTime());
        assertEquals("LAST TRADE", snapshot.priceKind());
    }

    @Test void usesFixedAlpacaDataEndpointAndHeaders() {
        AlpacaSettings settings = new AlpacaSettings("key", "secret", "iex", 30);
        HttpRequest request = new AlpacaMarketDataClient().requestFor("AAPL", settings);
        assertEquals("https://data.alpaca.markets/v2/stocks/AAPL/snapshot?feed=iex",
                request.uri().toString());
        assertEquals("key", request.headers().firstValue("APCA-API-KEY-ID").orElseThrow());
        assertEquals("secret", request.headers().firstValue("APCA-API-SECRET-KEY").orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> new AlpacaMarketDataClient().requestFor("AAPL/../orders", settings));
    }

    @Test void rejectsMissingPrice() {
        assertThrows(IOException.class,
                () -> AlpacaMarketDataClient.parseSnapshot("AAPL", "iex", "{\"symbol\":\"AAPL\"}"));
    }
}
