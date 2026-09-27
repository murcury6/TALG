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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A bounded, explicitly armed PAPER-only order-lifecycle test. No live endpoint exists here. */
final class PaperTestRunner implements AutoCloseable {
    static final ZoneId EASTERN = ZoneId.of("America/New_York");
    static final ObjectMapper JSON = new ObjectMapper();
    static final String ENDPOINT = "https://paper-api.alpaca.markets";
    record Plan(String date, String symbol, int intervalSeconds, int roundTrips, double cap, String strategy) {
        Plan(String date, String symbol, int intervalSeconds, int roundTrips, double cap) {
            this(date, symbol, intervalSeconds, roundTrips, cap, "smoke");
        }
        Plan {
            if (strategy == null) strategy = "smoke";
            if (!Set.of("smoke", "opening-range-v1").contains(strategy)) throw new IllegalArgumentException("Unknown paper strategy");
            if (strategy.equals("opening-range-v1") && roundTrips > 2) throw new IllegalArgumentException("Opening range pilot permits at most two trades");
            LocalDate.parse(date);
            if (!"SPY".equals(symbol) || intervalSeconds < 300 || intervalSeconds > 1800
                    || roundTrips < 1 || roundTrips > 12 || cap <= 0 || cap > 1000 || !Double.isFinite(cap))
                throw new IllegalArgumentException("Paper test: SPY, one share, 5–30 minute intervals, at most 12 round trips and $1,000 per order.");
        }
        Instant start() { return LocalDate.parse(date).atTime(9, 35).atZone(EASTERN).toInstant(); }
        Instant end() { return LocalDate.parse(date).atTime(12, 0).atZone(EASTERN).toInstant(); }
        boolean signals() { return strategy.equals("opening-range-v1"); }
    }
    static final class Session {
        public Plan plan;
        public String id = "", accountId = "", phase = "IDLE", clientId = "", orderId = "", side = "";
        public String sentAt = "", nextAt = "", message = "No paper test armed.";
        public BigDecimal owned = BigDecimal.ZERO, requested = BigDecimal.ZERO;
        public int completed, submissions;
        public boolean stopRequested, cancelRequested;
        public String signalAt = "", entryAt = "", exitReason = "";
        public double risk, entryPrice, stopPrice, targetPrice, highWater, realizedGross, estimatedNet;
        public int consecutiveLosses;
    }
    record Check(String accountId, boolean open, BigDecimal position, boolean otherOrders) {}
    record Order(String id, String clientId, String symbol, String side, String status, BigDecimal filled, double averagePrice) {
        Order(String id, String clientId, String symbol, String side, String status, BigDecimal filled) {
            this(id, clientId, symbol, side, status, filled, 0);
        }
    }
    interface Broker {
        String validatePlan(Plan plan) throws Exception;
        Check check(String symbol, String ownOrderId) throws Exception;
        BigDecimal limit(String symbol, String side, BigDecimal qty, double cap, Instant now) throws Exception;
        String submit(String symbol, String side, BigDecimal qty, BigDecimal limit, String clientId) throws Exception;
        Order order(String id) throws Exception;
        void cancel(String id) throws Exception;
        default IntradayMomentum.Signal signal(Instant now) throws Exception { throw new IOException("Signal data unavailable"); }
        default IntradayMarketData.Quote quote() throws Exception { throw new IOException("Quote unavailable"); }
    }
    private final Path folder;
    private Session session = new Session();
    private FileChannel lockChannel;
    private FileLock lock;

    PaperTestRunner(Path root) throws IOException {
        folder = root.resolve("work/paper-test");
        Path file = folder.resolve("session.json");
        if (Files.exists(file)) {
            session = JSON.readValue(file.toFile(), Session.class);
            if (session.plan == null || session.owned == null || session.owned.signum() < 0 || session.owned.compareTo(BigDecimal.ONE) > 0)
                throw new IOException("Invalid paper session file");
            if (session.phase.equals("SUBMITTING")) {
                session.phase = "PAUSED";
                session.message = "Submission interrupted. Reconcile client ID " + session.clientId + " in Alpaca before any new session.";
            }
        }
    }
    synchronized Session state() { return session; }
    synchronized String description() {
        if (session.plan == null) return session.message;
        return session.phase + " • " + session.plan.date() + " • SPY • 1 share maximum • " + session.completed + "/"
                + session.plan.roundTrips() + " round trips • Owned: " + session.owned + "\n"
                + (session.plan.signals() ? String.format(java.util.Locale.ROOT,
                    "Opening range v1 • Realized gross: $%.2f • Net with assumed costs: $%.2f\nEntry: %.2f • Stop trigger: %.2f • Target trigger: %.2f\n",
                    session.realizedGross, session.estimatedNet, session.entryPrice, session.stopPrice, session.targetPrice) : "") + session.message;
    }
    synchronized boolean active() { return Set.of("ARMED", "WAITING", "HOLDING", "PENDING", "SUBMITTING").contains(session.phase); }

    private void acquire() throws IOException {
        if (lock != null && lock.isValid()) return;
        Files.createDirectories(folder);
        lockChannel = FileChannel.open(folder.resolve("runner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { lock = lockChannel.tryLock(); }
        catch (java.nio.channels.OverlappingFileLockException error) { lock = null; }
        if (lock == null) { lockChannel.close(); lockChannel = null; throw new IOException("Another TALG window owns the paper test. Use that window."); }
    }

    synchronized void arm(Plan plan, Broker broker, String mode, Instant now) throws Exception {
        if (!"paper".equals(mode)) throw new IOException("Connect the PAPER account before arming this test.");
        if (now.isAfter(plan.end()) || plan.start().isAfter(now.plus(Duration.ofDays(7)))) throw new IOException("Choose a trading date within the next seven days.");
        acquire();
        // Re-read under the process lock before replacing any prior session.
        Path file = folder.resolve("session.json");
        if (Files.exists(file)) {
            Session disk = JSON.readValue(file.toFile(), Session.class);
            if (!Set.of("COMPLETE", "IDLE").contains(disk.phase))
                throw new IOException("An existing session is " + disk.phase + ". Resume/review it instead of creating a duplicate.");
        }
        String accountId = broker.validatePlan(plan);
        Check check = broker.check(plan.symbol(), "");
        if (!accountId.equals(check.accountId()) || check.position().signum() != 0 || check.otherOrders())
            throw new IOException("Start with no SPY position and no open SPY orders in this paper account.");
        session = new Session(); session.plan = plan; session.id = UUID.randomUUID().toString();
        session.accountId = accountId; session.phase = "ARMED"; session.nextAt = plan.start().toString();
        session.message = "Armed for 09:35–12:00 Eastern. " + (plan.signals() ? "Signal entries 09:45–11:00; no forced trades. " : "")
                + "Keep this TALG window open and the computer awake. No order sent yet.";
        save(); log("ARMED", Map.of("plan", plan, "sessionId", session.id));
    }

    synchronized void requestStop() throws Exception {
        acquire();
        session.stopRequested = true;
        if (Set.of("ARMED", "WAITING").contains(session.phase) && session.owned.signum() == 0) {
            session.phase = "COMPLETE"; session.message = "Stopped flat; no more orders will be sent.";
        } else {
            session.nextAt = Instant.now().toString();
            session.message = "Stop requested: cancel any pending entry and close only this test's filled position when possible.";
        }
        save(); log("STOP_REQUESTED", Map.of("owned", session.owned));
    }

    synchronized void tick(Broker broker, String mode, Instant now) throws Exception {
        if (!active()) return;
        acquire();
        if (!"paper".equals(mode)) { pause("Paper connection changed; no order sent."); return; }
        if (now.isBefore(session.plan.start())) return;
        if (!now.isBefore(session.plan.end())) session.stopRequested = true;
        if (session.phase.equals("SUBMITTING")) { pause("Uncertain submission. Review client ID " + session.clientId + "; never automatically resubmitted."); return; }
        try {
            Check check = broker.check(session.plan.symbol(), session.orderId);
            if (!session.accountId.equals(check.accountId())) { pause("Connected paper account differs from the armed account."); return; }
            if (check.otherOrders()) { pause("Another SPY order exists. Test paused to avoid interfering with it."); return; }
            if (session.phase.equals("PENDING")) {
                reconcile(broker, now);
                return;
            }
            if (check.position().compareTo(session.owned) != 0) { pause("SPY position differs from this test's recorded fills. Review the paper account."); return; }
            if (session.stopRequested && session.owned.signum() == 0) { complete("Session ended flat."); return; }
            if (!check.open()) {
                if (session.stopRequested) pause("Market closed before the test could finish. Recorded remaining SPY: " + session.owned);
                else { session.message = "Waiting for Alpaca's regular-session market clock."; save(); }
                return;
            }
            if (!session.stopRequested && (!session.plan.signals() || session.owned.signum() == 0) && now.isBefore(Instant.parse(session.nextAt))) return;
            if (session.owned.signum() == 0 && session.completed >= session.plan.roundTrips()) { complete("All test round trips completed; account is flat in SPY."); return; }
            if (session.plan.signals() && !session.stopRequested && !signalAction(broker, now)) return;
            String side = session.owned.signum() == 0 ? "buy" : "sell";
            BigDecimal qty = side.equals("buy") ? BigDecimal.ONE : session.owned;
            BigDecimal limit = broker.limit(session.plan.symbol(), side, qty, session.plan.cap(), now);
            if (session.plan.signals() && side.equals("buy") && limit.doubleValue() > entryCeiling) {
                session.message = "Skipped breakout: price moved beyond the entry limit."; save(); return;
            }
            session.side = side; session.requested = qty; session.clientId = "talg-test-" + UUID.randomUUID();
            session.sentAt = now.toString(); session.cancelRequested = false; session.orderId = "";
            session.phase = "SUBMITTING"; session.submissions++;
            session.message = "Submitting paper " + side + " " + qty + " SPY; client ID " + session.clientId;
            save(); // Durable intent BEFORE the network mutation; interruption cannot cause an automatic duplicate.
            log("SUBMIT_INTENT", Map.of("side", side, "qty", qty, "limit", limit, "clientOrderId", session.clientId));
            session.orderId = broker.submit(session.plan.symbol(), side, qty, limit, session.clientId);
            session.phase = "PENDING"; session.message = "Paper " + side + " accepted; waiting for the broker's fill status.";
            save(); log("ORDER_ACCEPTED", Map.of("orderId", session.orderId, "clientOrderId", session.clientId));
        } catch (Exception error) {
            pause("Stopped on error: " + safeError(error) + (session.clientId.isBlank() ? "" : " • Last client ID: " + session.clientId));
        }
    }

    private double entryCeiling;
    /** Entry decisions use closed bars; exits are checked on every poll, independent of entry cooldown. */
    private boolean signalAction(Broker broker, Instant now) throws Exception {
        if (session.owned.signum() == 0) {
            if (session.estimatedNet <= -8 || session.consecutiveLosses >= 2) { complete("Daily paper loss limit reached; flat."); return false; }
            if (!now.atZone(EASTERN).toLocalTime().isBefore(LocalTime.of(11, 0))) { complete("Entry window ended; flat."); return false; }
            try {
                var signal = broker.signal(now);
                if (signal.asOf().isBlank()) { session.message = signal.reason(); save(); return false; }
                if (signal.asOf().equals(session.signalAt)) return false;
                session.signalAt = signal.asOf(); session.message = signal.reason();
                log("SIGNAL", signal); save();
                if (!signal.enter()) return false;
                long age = Duration.between(Instant.parse(signal.asOf()), now).getSeconds();
                if (age < 0 || age > 90 || !Double.isFinite(signal.risk()) || signal.risk() <= 0) return false;
                var quote = broker.quote();
                double midpoint = (quote.bid() + quote.ask()) / 2;
                if ((quote.ask() - quote.bid()) / midpoint > .0005) { session.message = "Skipped entry: spread above five basis points."; save(); return false; }
                if (signal.risk() + midpoint * .0004 + .02 > 8 + session.estimatedNet) {
                    session.message = "Skipped entry: planned risk exceeds remaining $8 daily budget."; save(); return false;
                }
                entryCeiling = signal.close() + signal.atr() * .5;
                if (quote.ask() + .01 > entryCeiling) { session.message = "Skipped entry: breakout has moved too far."; save(); return false; }
                session.risk = signal.risk(); session.exitReason = ""; save(); return true;
            } catch (Exception error) {
                session.message = "Entry skipped: " + safeError(error); save(); return false;
            }
        }
        var quote = broker.quote();
        double bid = quote.bid();
        session.highWater = Math.max(session.highWater, bid);
        if (session.highWater >= session.entryPrice + session.risk)
            session.stopPrice = Math.max(session.stopPrice, session.highWater - session.risk);
        long held = Duration.between(Instant.parse(session.entryAt), now).getSeconds();
        String reason = bid <= session.stopPrice ? "stop trigger" : bid >= session.targetPrice ? "profit target"
                : held >= 3600 ? "60-minute time exit" : "";
        if (reason.isEmpty() && held >= 600) {
            try { if (broker.signal(now).trendExit()) reason = "two closes below session VWAP"; }
            catch (Exception error) { session.message = "Trend data unavailable; quote-based exits remain active."; }
        }
        if (reason.isEmpty()) { save(); return false; }
        session.exitReason = reason; log("EXIT_TRIGGER", Map.of("reason", reason, "bid", bid, "stop", session.stopPrice, "target", session.targetPrice));
        save(); return true;
    }

    private void reconcile(Broker broker, Instant now) throws Exception {
        Order order = broker.order(session.orderId);
        if (!order.id().equals(session.orderId) || !order.clientId().equals(session.clientId)
                || !order.symbol().equals(session.plan.symbol()) || !order.side().equals(session.side)
                || order.filled().signum() < 0 || order.filled().compareTo(session.requested) > 0)
            throw new IOException("Broker order does not match the recorded test intent");
        boolean terminal = Set.of("filled", "canceled", "expired", "rejected").contains(order.status());
        if (!terminal) {
            if (!session.cancelRequested && (Duration.between(Instant.parse(session.sentAt), now).getSeconds() >= 120
                    || session.stopRequested && session.side.equals("buy"))) {
                session.cancelRequested = true; save();
                broker.cancel(session.orderId);
                log("CANCEL_REQUESTED", Map.of("orderId", session.orderId));
            }
            session.message = "Paper " + session.side + " " + order.status() + " • filled " + order.filled()
                    + (session.cancelRequested ? " • cancellation requested; awaiting terminal status" : "");
            save(); return;
        }
        log("ORDER_TERMINAL", Map.of("order", order));
        if (session.plan.signals() && order.filled().signum() > 0 && (!Double.isFinite(order.averagePrice()) || order.averagePrice() <= 0))
            throw new IOException("Filled paper order has no valid average execution price; reconcile before continuing");
        if (session.side.equals("buy")) {
            session.owned = order.filled();
            if (session.owned.signum() == 0) { pause("Entry ended " + order.status() + " without a fill; test stopped flat."); return; }
            session.phase = "HOLDING";
            if (session.plan.signals()) {
                session.entryPrice = order.averagePrice(); session.entryAt = now.toString(); session.highWater = session.entryPrice;
                session.stopPrice = session.entryPrice - session.risk; session.targetPrice = session.entryPrice + 2 * session.risk;
                log("POSITION_OPEN", Map.of("entry", session.entryPrice, "risk", session.risk, "stop", session.stopPrice, "target", session.targetPrice));
            }
            if (!order.status().equals("filled")) session.stopRequested = true;
        } else {
            if (session.plan.signals() && order.filled().signum() > 0) {
                double qty = order.filled().doubleValue();
                double gross = (order.averagePrice() - session.entryPrice) * qty;
                double net = gross - (order.averagePrice() + session.entryPrice) * qty * .0002 - .02;
                session.realizedGross += gross; session.estimatedNet += net;
                session.consecutiveLosses = net < 0 ? session.consecutiveLosses + 1 : 0;
                log("REALIZED_PNL", Map.of("gross", gross, "estimatedNet", net, "dailyEstimatedNet", session.estimatedNet,
                        "costAssumption", "2bps each side plus $0.01 per order; estimated, not actual fees"));
            }
            session.owned = session.owned.subtract(order.filled());
            if (session.owned.signum() != 0) { pause("Exit ended " + order.status() + "; remaining test position " + session.owned + " SPY requires review."); return; }
            session.completed++; session.phase = "WAITING";
        }
        session.orderId = ""; session.clientId = ""; session.requested = BigDecimal.ZERO;
        session.nextAt = now.plusSeconds(session.plan.intervalSeconds()).toString();
        session.message = "Fill recorded. " + session.completed + " round trips complete. Next action after " + session.nextAt;
        if (session.owned.signum() == 0 && (session.completed >= session.plan.roundTrips() || session.stopRequested
                || session.plan.signals() && (session.estimatedNet <= -8 || session.consecutiveLosses >= 2))) complete("Paper session complete and flat in SPY.");
        else save();
    }

    private void complete(String message) throws Exception { session.phase = "COMPLETE"; session.message = message; save(); log("COMPLETE", Map.of("roundTrips", session.completed)); }
    private void pause(String message) throws Exception { session.phase = "PAUSED"; session.message = message; save(); log("PAUSED", Map.of("reason", message, "owned", session.owned)); }
    private void save() throws IOException {
        Files.createDirectories(folder);
        Path temp = Files.createTempFile(folder, "session-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), session);
            try { Files.move(temp, folder.resolve("session.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException error) { Files.move(temp, folder.resolve("session.json"), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private void log(String event, Object detail) throws IOException {
        Files.writeString(folder.resolve("events-" + session.plan.date() + ".jsonl"), JSON.writeValueAsString(Map.of("at", Instant.now().toString(),
                "sessionId", session.id, "event", event, "detail", detail)) + "\n", java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    static String safeError(Exception error) { return error instanceof IOException ? error.getMessage() : error.getClass().getSimpleName(); }
    @Override public synchronized void close() throws IOException { if (lock != null) lock.release(); if (lockChannel != null) lockChannel.close(); lock = null; lockChannel = null; }

    static final class AlpacaBroker implements Broker {
        private final AlpacaSettings settings;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        AlpacaBroker(AlpacaSettings settings) {
            if (settings == null) throw new IllegalArgumentException("Connect the paper account in Settings first.");
            if (settings.feed().equals("delayed_sip")) throw new IllegalArgumentException("Use IEX or SIP for the paper test.");
            this.settings = settings;
        }
        JsonNode call(String path, String method, String body, boolean missingAllowed) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(ENDPOINT + path)).timeout(Duration.ofSeconds(12))
                    .header("APCA-API-KEY-ID", settings.apiKey()).header("APCA-API-SECRET-KEY", settings.apiSecret())
                    .header("Accept", "application/json");
            if (method.equals("POST")) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            else if (method.equals("DELETE")) request.DELETE(); else request.GET();
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (missingAllowed && response.statusCode() == 404) return JSON.nullNode();
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IOException("Paper API " + method + " returned HTTP " + response.statusCode() + "; no automatic submission retry.");
            return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
        }
        public String validatePlan(Plan plan) throws Exception {
            JsonNode account = account();
            JsonNode calendar = call("/v2/calendar?start=" + plan.date() + "&end=" + plan.date(), "GET", "", false);
            if (!calendar.isArray() || calendar.size() != 1 || !calendar.get(0).path("date").asText().equals(plan.date()))
                throw new IOException("The requested date is not an Alpaca trading day.");
            LocalTime open = LocalTime.parse(calendar.get(0).path("open").asText());
            LocalTime close = LocalTime.parse(calendar.get(0).path("close").asText());
            if (open.isAfter(LocalTime.of(9, 35)) || !close.isAfter(LocalTime.NOON)) throw new IOException("The test window does not fit this trading session.");
            JsonNode asset = call("/v2/assets/" + plan.symbol(), "GET", "", false);
            if (!asset.path("tradable").asBoolean(false) || !"active".equals(asset.path("status").asText())) throw new IOException("SPY is not active/tradable in the paper account.");
            return account.path("id").asText();
        }
        JsonNode account() throws Exception {
            JsonNode account = call("/v2/account", "GET", "", false);
            if (!account.isObject() || account.path("id").asText().isBlank() || !"ACTIVE".equals(account.path("status").asText())
                    || account.path("trading_blocked").asBoolean(true) || account.path("account_blocked").asBoolean(true))
                throw new IOException("Paper account is not active and unblocked for trading.");
            return account;
        }
        public Check check(String symbol, String ownOrderId) throws Exception {
            JsonNode account = account();
            JsonNode clock = call("/v2/clock", "GET", "", false);
            JsonNode position = call("/v2/positions/" + symbol, "GET", "", true);
            BigDecimal qty = position.isNull() ? BigDecimal.ZERO : decimal(position.path("qty"));
            JsonNode orders = call("/v2/orders?status=open&symbols=" + symbol + "&limit=500", "GET", "", false);
            if (!orders.isArray()) throw new IOException("Invalid paper order list");
            boolean others = false;
            for (var order : orders) if (!order.path("id").asText().equals(ownOrderId)) others = true;
            return new Check(account.path("id").asText(), clock.path("is_open").asBoolean(false), qty, others);
        }
        public BigDecimal limit(String symbol, String side, BigDecimal qty, double cap, Instant now) throws Exception {
            if (qty.signum() <= 0 || qty.compareTo(BigDecimal.ONE) > 0) throw new IOException("Test quantity must be above zero and at most one share");
            var quote = quote();
            BigDecimal limit = BigDecimal.valueOf(side.equals("buy") ? quote.ask() + .01 : quote.bid() - .01)
                    .setScale(2, side.equals("buy") ? RoundingMode.CEILING : RoundingMode.FLOOR);
            if (limit.signum() <= 0 || limit.multiply(qty).compareTo(BigDecimal.valueOf(cap)) > 0) throw new IOException("Paper order exceeds the $" + cap + " cap");
            if (side.equals("buy") && decimal(account().path("buying_power")).compareTo(limit.multiply(qty)) < 0) throw new IOException("Insufficient paper buying power");
            return limit;
        }
        public String submit(String symbol, String side, BigDecimal qty, BigDecimal limit, String clientId) throws Exception {
            // Recheck the exchange clock immediately before every POST.
            if (!call("/v2/clock", "GET", "", false).path("is_open").asBoolean(false)) throw new IOException("Regular session is closed");
            String body = JSON.createObjectNode().put("symbol", symbol).put("side", side).put("qty", qty.toPlainString())
                    .put("type", "limit").put("limit_price", limit.toPlainString()).put("time_in_force", "day")
                    .put("extended_hours", false).put("client_order_id", clientId).toString();
            JsonNode order = call("/v2/orders", "POST", body, false);
            String id = order.path("id").asText();
            if (id.isBlank() || !order.path("client_order_id").asText().equals(clientId)) throw new IOException("Unclear paper order response; reconcile the recorded client ID");
            UUID.fromString(id);
            return id;
        }
        public Order order(String id) throws Exception {
            JsonNode order = call("/v2/orders/" + UUID.fromString(id), "GET", "", false);
            return new Order(order.path("id").asText(), order.path("client_order_id").asText(), order.path("symbol").asText(),
                    order.path("side").asText(), order.path("status").asText(), decimal(order.path("filled_qty")), order.path("filled_avg_price").asDouble(0));
        }
        public IntradayMomentum.Signal signal(Instant now) throws Exception { return new IntradayMarketData(settings).signal(now); }
        public IntradayMarketData.Quote quote() throws Exception { return new IntradayMarketData(settings).quote(); }
        public void cancel(String id) throws Exception { call("/v2/orders/" + UUID.fromString(id), "DELETE", "", false); }
        private static BigDecimal decimal(JsonNode node) throws IOException {
            try { return new BigDecimal(node.asText()); } catch (RuntimeException error) { throw new IOException("Invalid quantity or buying power from paper broker"); }
        }
    }
}
