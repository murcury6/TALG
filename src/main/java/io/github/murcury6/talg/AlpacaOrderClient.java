package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Explicit day-limit orders only; mode fixes the paper or live trading endpoint. */
final class AlpacaOrderClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Transport transport;
    private final QuoteSource market;

    AlpacaOrderClient() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        this.transport = request -> {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        };
        this.market = new AlpacaMarketDataClient()::fetch;
    }

    AlpacaOrderClient(Transport transport, QuoteSource market) {
        this.transport = transport;
        this.market = market;
    }

    @FunctionalInterface interface Transport {
        Response send(HttpRequest request) throws IOException, InterruptedException;
    }
    @FunctionalInterface interface QuoteSource {
        MarketSnapshot fetch(String symbol, AlpacaSettings settings) throws IOException, InterruptedException;
    }
    record Response(int statusCode, String body) {}

    record Intent(String symbol, String side, int quantity, double maxNotional) {
        Intent {
            if (symbol == null || !symbol.matches("[A-Z][A-Z0-9.-]{0,9}"))
                throw new IllegalArgumentException("Enter a valid stock ticker.");
            if (!"buy".equals(side) && !"sell".equals(side))
                throw new IllegalArgumentException("Side must be buy or sell.");
            if (quantity < 1 || quantity > 100)
                throw new IllegalArgumentException("Quantity must be 1–100 whole shares.");
            if (!Double.isFinite(maxNotional) || maxNotional <= 0 || maxNotional > 2500)
                throw new IllegalArgumentException("Per-order cap must be above $0 and at most $2,500.");
        }
    }

    record Preview(Intent intent, String mode, double quotePrice, Instant quoteTime,
                   BigDecimal limitPrice, BigDecimal maxValue, Instant checkedAt) {}
    record Submitted(UUID orderId, String clientOrderId, String status, String mode) {}
    record OpenOrder(UUID id, String symbol, String side, String quantity,
                     String limitPrice, String status) {}
    record Position(String symbol, String side, BigDecimal quantity, BigDecimal averagePrice,
                    BigDecimal currentPrice, BigDecimal marketValue, BigDecimal unrealizedProfit,
                    BigDecimal unrealizedPercent) {}

    JsonNode availableAssets(AlpacaSettings settings, String mode) throws IOException, InterruptedException {
        Response response = transport.send(request(endpoint(mode) + "/v2/assets?status=active&asset_class=us_equity", settings).GET().build());
        if (response.statusCode() != 200) throw new IOException("Asset catalogue returned HTTP " + response.statusCode() + ".");
        JsonNode result = JSON.readTree(response.body());
        if (result == null || !result.isArray()) throw new IOException("Invalid asset catalogue response.");
        var assets = JSON.createArrayNode(); var seen = new java.util.HashSet<String>();
        for (JsonNode asset : result) {
            if (!asset.path("status").asText().equals("active") || !asset.path("tradable").asBoolean()
                    || !asset.path("class").asText().equals("us_equity")) continue;
            String symbol = asset.path("symbol").asText();
            if (!symbol.matches("[A-Z][A-Z0-9.\\-]{0,14}")) throw new IOException("Unsupported symbol in asset catalogue: " + symbol);
            if (seen.add(symbol)) assets.add(asset);
        }
        if (assets.isEmpty()) throw new IOException("Broker returned no active tradable US equities.");
        return JSON.createObjectNode().put("source", "alpaca").put("scope", "active_tradable_us_equity")
                .put("fetched_at", Instant.now().toString()).put("account_mode", mode).set("assets", assets);
    }

    List<Position> positions(AlpacaSettings settings, String mode) throws IOException, InterruptedException {
        Response response = transport.send(request(endpoint(mode) + "/v2/positions", settings).GET().build());
        if (response.statusCode() != 200) throw new IOException("Positions returned HTTP " + response.statusCode() + ".");
        JsonNode result = JSON.readTree(response.body());
        if (result == null || !result.isArray()) throw new IOException("Invalid positions response.");
        List<Position> positions = new ArrayList<>();
        for (JsonNode row : result) {
            String symbol = row.path("symbol").asText(), side = row.path("side").asText();
            if (symbol.isBlank() || !(side.equals("long") || side.equals("short"))) throw new IOException("Invalid position identity.");
            positions.add(new Position(symbol, side, decimal(row.path("qty"), "quantity"),
                    decimal(row.path("avg_entry_price"), "average entry price"), decimal(row.path("current_price"), "current price"),
                    decimal(row.path("market_value"), "market value"), decimal(row.path("unrealized_pl"), "unrealized P/L"),
                    decimal(row.path("unrealized_plpc"), "unrealized P/L percent").multiply(BigDecimal.valueOf(100))));
        }
        return List.copyOf(positions);
    }

    Preview preview(Intent intent, AlpacaSettings settings, String mode)
            throws IOException, InterruptedException {
        String endpoint = endpoint(mode);
        if ("delayed_sip".equals(settings.feed()))
            throw new IOException("Delayed SIP feed cannot be used to preview an order. Choose IEX or SIP.");
        JsonNode clock = get(endpoint + "/v2/clock", settings);
        if (!clock.path("is_open").asBoolean(false))
            throw new IOException("The US stock market is not open. TALG will not queue a day order.");
        JsonNode account = get(endpoint + "/v2/account", settings);
        if (!"ACTIVE".equals(account.path("status").asText())
                || account.path("trading_blocked").asBoolean(true))
            throw new IOException("This Alpaca account is not active for trading.");
        MarketSnapshot quote = market.fetch(intent.symbol(), settings);
        Duration age = Duration.between(quote.priceTime(), Instant.now());
        if (age.isNegative() || age.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IOException("The Alpaca quote is more than five minutes old; no order will be sent.");
        BigDecimal price = BigDecimal.valueOf(quote.price());
        BigDecimal limit = (intent.side().equals("buy")
                ? price.multiply(new BigDecimal("1.005")).setScale(2, RoundingMode.CEILING)
                : price.multiply(new BigDecimal("0.995")).setScale(2, RoundingMode.FLOOR));
        if (limit.signum() <= 0) throw new IOException("Invalid order limit price.");
        BigDecimal maximum = limit.multiply(BigDecimal.valueOf(intent.quantity()));
        if (maximum.compareTo(BigDecimal.valueOf(intent.maxNotional())) > 0)
            throw new IOException("The limit-price notional exceeds your per-order cap.");
        if (intent.side().equals("buy")) {
            BigDecimal power = decimal(account.path("buying_power"), "buying power");
            if (power.compareTo(maximum) < 0) throw new IOException("Insufficient Alpaca buying power.");
        } else {
            JsonNode position = get(endpoint + "/v2/positions/" + intent.symbol(), settings);
            BigDecimal held = decimal(position.path("qty"), "held quantity");
            if (held.compareTo(BigDecimal.valueOf(intent.quantity())) < 0)
                throw new IOException("Sell quantity exceeds the current long position; shorting is disabled here.");
        }
        return new Preview(intent, mode, quote.price(), quote.priceTime(), limit, maximum, Instant.now());
    }

    Submitted submit(Preview approved, AlpacaSettings settings, String mode)
            throws IOException, InterruptedException {
        if (!approved.mode().equals(mode)) throw new IOException("Account mode changed since preview.");
        if (Duration.between(approved.checkedAt(), Instant.now()).compareTo(Duration.ofMinutes(1)) > 0)
            throw new IOException("Order preview expired; preview again.");
        Preview current = preview(approved.intent(), settings, mode);
        if (current.limitPrice().compareTo(approved.limitPrice()) != 0)
            throw new IOException("Quote or limit price changed; preview again.");
        String clientId = "talg-" + UUID.randomUUID();
        HttpRequest request = orderRequest(approved.intent(), approved.limitPrice(), clientId, settings, mode);
        Response response;
        try { response = transport.send(request); }
        catch (IOException error) {
            throw new IOException("Order submission outcome is unknown. Check Alpaca Orders using client ID "
                    + clientId + " before trying again.", error);
        }
        if (response.statusCode() != 200 && response.statusCode() != 201)
            throw new IOException("Alpaca order submission returned HTTP " + response.statusCode()
                    + ". No automatic retry was attempted; check Orders before trying again.");
        JsonNode result = JSON.readTree(response.body());
        try {
            UUID orderId = UUID.fromString(result.path("id").asText());
            String status = result.path("status").asText();
            if (status.isBlank()) throw new IllegalArgumentException("missing status");
            return new Submitted(orderId, clientId, status, mode);
        } catch (RuntimeException error) {
            throw new IOException("Order response was unclear. Check Alpaca Orders using client ID "
                    + clientId + "; do not submit again yet.", error);
        }
    }

    HttpRequest orderRequest(Intent intent, BigDecimal limitPrice, String clientId,
                             AlpacaSettings settings, String mode) throws IOException {
        String body = JSON.createObjectNode().put("symbol", intent.symbol())
                .put("qty", Integer.toString(intent.quantity())).put("side", intent.side())
                .put("type", "limit").put("time_in_force", "day")
                .put("limit_price", limitPrice.toPlainString()).put("extended_hours", false)
                .put("client_order_id", clientId).toString();
        return request(endpoint(mode) + "/v2/orders", settings)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    String status(UUID orderId, AlpacaSettings settings, String mode) throws IOException, InterruptedException {
        JsonNode result = get(endpoint(mode) + "/v2/orders/" + orderId, settings);
        return result.path("status").asText("unknown");
    }

    void cancel(UUID orderId, AlpacaSettings settings, String mode) throws IOException, InterruptedException {
        Response response = transport.send(request(endpoint(mode) + "/v2/orders/" + orderId, settings)
                .DELETE().build());
        if (response.statusCode() != 204)
            throw new IOException("Cancel request returned HTTP " + response.statusCode()
                    + "; check the order status before further action.");
    }

    List<OpenOrder> openOrders(AlpacaSettings settings, String mode)
            throws IOException, InterruptedException {
        Response response = transport.send(request(endpoint(mode)
                + "/v2/orders?status=open&limit=50&direction=desc", settings).GET().build());
        if (response.statusCode() != 200)
            throw new IOException("Alpaca open orders returned HTTP " + response.statusCode() + ".");
        JsonNode result = JSON.readTree(response.body());
        if (result == null || !result.isArray()) throw new IOException("Alpaca open orders response is invalid.");
        List<OpenOrder> orders = new ArrayList<>();
        for (JsonNode row : result) {
            try {
                orders.add(new OpenOrder(UUID.fromString(row.path("id").asText()),
                        row.path("symbol").asText(), row.path("side").asText(),
                        row.path("qty").asText(), row.path("limit_price").asText(),
                        row.path("status").asText()));
            } catch (RuntimeException error) {
                throw new IOException("Alpaca returned an invalid open order.", error);
            }
        }
        return List.copyOf(orders);
    }

    private JsonNode get(String uri, AlpacaSettings settings) throws IOException, InterruptedException {
        Response response = transport.send(request(uri, settings).GET().build());
        if (response.statusCode() != 200)
            throw new IOException("Alpaca trading check returned HTTP " + response.statusCode() + ".");
        JsonNode result = JSON.readTree(response.body());
        if (result == null || !result.isObject()) throw new IOException("Alpaca trading response is invalid.");
        return result;
    }

    private static HttpRequest.Builder request(String uri, AlpacaSettings settings) {
        return HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(12))
                .header("APCA-API-KEY-ID", settings.apiKey())
                .header("APCA-API-SECRET-KEY", settings.apiSecret())
                .header("Accept", "application/json");
    }

    private static String endpoint(String mode) {
        return switch (mode) {
            case "paper" -> "https://paper-api.alpaca.markets";
            case "live" -> "https://api.alpaca.markets";
            default -> throw new IllegalArgumentException("Select a paper or live Alpaca account mode.");
        };
    }

    private static BigDecimal decimal(JsonNode node, String label) throws IOException {
        try { return new BigDecimal(node.asText()); }
        catch (RuntimeException error) { throw new IOException("Alpaca did not provide valid " + label + ".", error); }
    }
}
