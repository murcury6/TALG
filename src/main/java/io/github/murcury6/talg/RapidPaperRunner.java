package io.github.murcury6.talg;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Bounded multi-symbol PAPER execution. Pending entries reserve portfolio slots before POST. */
final class RapidPaperRunner implements AutoCloseable {
    static final List<String> SYMBOLS = List.of("AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "AMD", "AVGO", "NFLX",
            "INTC", "MU", "PLTR", "UBER", "JPM", "BAC", "XOM", "CVX", "WMT", "DIS");
    static final class Leg {
        public String phase = "WATCHING", orderId = "", clientId = "", side = "", sentAt = "", entryAt = "", nextAt = "", signalAt = "", message = "Warming up minute observations";
        public BigDecimal owned = BigDecimal.ZERO, requested = BigDecimal.ZERO;
        public double entry, risk, stop, target, high, gross, net;
        public int entries, completed, exitAttempts;
        public boolean cancelRequested, forceExit;
        public double orderLimit;
        public String entrySignalAt = "", checkedAt = "";
        public IntradayMarketData.Quote previousQuote;
        public String orderReason = "";
        public Map<String, Double> modelValues = new LinkedHashMap<>();
        public com.fasterxml.jackson.databind.JsonNode newsModelInputs, newsTrigger;
        public String modelEvaluatedAt = "", modelDecision = "", modelError = "";
        public List<IntradayMomentum.Bar> retainedBars = new ArrayList<>();
    }
    static final class Session {
        public String date = "", id = "", accountId = "", phase = "IDLE", message = "No rapid paper session armed", lastScan = "";
        public Map<String, Leg> legs = new LinkedHashMap<>();
        public List<String> postTimes = new ArrayList<>();
        public int submissions, entries, completed, scans;
        public long newsEventCursor;
        public double gross, net;
        public double tradedDollars, filledShares;
        public boolean stopRequested;
        public boolean userStopped;
        public boolean lossTriggered;
        public String nextSessionDate = "", stopReason = "";
        public String profileRevision, newsProfileRevision;
        public RapidPaperModel model;
        public StatisticalReturnModel statistics;
    }
    record Account(String id, boolean open, double buyingPower, Map<String, BigDecimal> positions, Map<String, String> openOrders) {}
    record Market(IntradayMarketData.Quote quote, IntradayMomentum.Bar bar) {}
    interface Broker {
        String validate(String date, List<String> symbols) throws Exception;
        Account account() throws Exception;
        Map<String, Market> market(List<String> symbols) throws Exception;
        String submit(String symbol, String side, BigDecimal qty, BigDecimal limit, String client) throws Exception;
        PaperTestRunner.Order order(String id) throws Exception;
        void cancel(String id) throws Exception;
        TradingSchedule.Day calendar(String date) throws Exception;
        String nextTradingDate(String afterDate) throws Exception;
        default String submit(String symbol, String side, BigDecimal qty, BigDecimal limit, String client, boolean extended) throws Exception {
            if (extended) throw new IOException("This broker adapter does not support extended hours");
            return submit(symbol, side, qty, limit, client);
        }
        default Map<String, List<IntradayMomentum.Bar>> seed(List<String> symbols, Instant now) throws Exception { return Map.of(); }
        default Map<String, String> seedIssues() { return Map.of(); }
    }
    private final Path root, folder, legacy, config;
    private Session session = new Session();
    private FileChannel channel;
    private FileLock lock;
    private final Map<String, List<IntradayMomentum.Bar>> history = new HashMap<>();
    private boolean seedAttempted;
    RapidPaperRunner(Path root) throws IOException {
        this.root = root; config = root.resolve("work/strategies/rapid-paper.json");
        if (!Files.exists(config)) { Files.createDirectories(config.getParent()); PaperTestRunner.JSON.writerWithDefaultPrettyPrinter().writeValue(config.toFile(), RapidPaperModel.defaults()); }
        folder = root.resolve("work/rapid-paper"); legacy = root.resolve("work/paper-test");
        if (Files.exists(folder.resolve("session.json"))) {
            session = PaperTestRunner.JSON.readValue(folder.resolve("session.json").toFile(), Session.class);
            if (session.stopReason.startsWith("Daily loss trigger")) session.lossTriggered = true;
            if (session.model == null || session.statistics == null || !session.legs.keySet().equals(new HashSet<>(session.model.universe()))) throw new IOException("Unexpected saved model/universe");
            for (var leg : session.legs.values()) {
                if (leg.owned == null || leg.owned.signum() < 0 || leg.owned.compareTo(BigDecimal.valueOf(session.model.sizing().shares())) > 0) throw new IOException("Invalid recorded position");
                if (leg.phase.equals("SUBMITTING")) { leg.phase = "REVIEW"; leg.message = "Interrupted submission: reconcile client ID " + leg.clientId; session.stopRequested = true; session.stopReason = "Uncertain submission requires review"; }
            }
        }
    }
    synchronized Session state() { return session; }
    private Path selectedConfig() throws IOException {
        var profiles = new ModelProfiles(root); return profiles.file("trade", profiles.selected("trade"), "config");
    }
    String modelSource() throws IOException { return Files.readString(selectedConfig()); }
    RapidPaperModel configuredModel() throws IOException { return PaperTestRunner.JSON.readValue(selectedConfig().toFile(), RapidPaperModel.class); }
    String configurationSummary() throws IOException {
        var m = active() ? session.model : configuredModel(); var s = m.schedule(); var p = m.portfolio();
        return (active() ? session.profileRevision == null ? "Armed legacy snapshot" : "Armed profile: " + new ModelProfiles(root).label(session.profileRevision) : "Next profile: " + new ModelProfiles(root).list("trade").get(new ModelProfiles(root).selected("trade"))) + "\nPAPER ONLY · " + m.universe().size() + " stocks/ETFs · up to " + m.sizing().shares() + " shares/symbol · " + p.maxPositions() + " positions · $" + m.sizing().maxEntryDollars() + "/entry · $" + m.sizing().maxExposureDollars() + " total exposure\n"
                + "Hours: " + s.start() + "–" + s.end() + " Eastern · Premarket/after-hours: " + s.extendedHours() + " · Repeat trading days: " + s.repeatTradingDays() + "\n"
                + "Daily entry cap: " + (p.maxEntries() == 0 ? "none" : p.maxEntries()) + " · Per-stock cap: " + (p.maxEntriesPerStock() == 0 ? "none" : p.maxEntriesPerStock())
                + " · Quote refresh: " + s.scanSeconds() + " sec · Order limit: " + m.execution().postsPerMinute() + "/min\n"
                + "Stop entries and close positions " + s.flattenMinutesBeforeEnd() + " min before end · Daily loss trigger: $" + p.lossDollars() + "\n"
                + (m.signal().bidRules().enabled() ? "Bid-driven entries/exits · timed holding exit OFF · edit Bid Rules / Speed & Size under Model bricks.\n" : "Edit Hours & Limits under Model bricks; save then arm. Stop disables daily repeat.\n")
                + "Statistical model-ranked volume experiment; no demonstrated profit edge, and no extended-hours model validation.\n"
                + "Free IEX feed: waits when quotes are stale/missing. Keep TALG open and awake; exit limits may not fill.";
    }
    synchronized void saveModel(String source) throws IOException {
        if (active()) throw new IOException("Stop the session before changing model bricks");
        var model = PaperTestRunner.JSON.readValue(source, RapidPaperModel.class);
        PaperTestRunner.JSON.writerWithDefaultPrettyPrinter().writeValue(selectedConfig().toFile(), model);
    }
    synchronized boolean active() { return Set.of("RUNNING", "STOPPING", "WAITING_NEXT").contains(session.phase); }
    synchronized String description() {
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                "%s · %s · %d/%s entries · %d completed round trips · %d order submissions\nGross $%.2f · Estimated net $%.2f · Scans %d · Last scan %s\n%s\n\n",
                session.phase, session.date, session.entries, session.model == null || session.model.portfolio().maxEntries() == 0 ? "no cap" : String.valueOf(session.model.portfolio().maxEntries()), session.completed, session.submissions, session.gross, session.net, session.scans, session.lastScan, session.message));
        if (session.model != null) text.append("Hours ET: " + session.model.schedule().start() + "–" + session.model.schedule().end() + " · Extended: " + session.model.schedule().extendedHours()
                + " · Repeat trading days: " + session.model.schedule().repeatTradingDays() + (session.nextSessionDate.isBlank() ? "" : " · Next: " + session.nextSessionDate) + "\n");
        if (session.profileRevision != null) text.append("Armed profile revision: " + session.profileRevision + "\nNews revision: " + session.newsProfileRevision + "\n");
        if (session.statistics != null) text.append("Model: ridge regression · 2-minute return · trained through " + session.statistics.trainedThrough() + "\n");
        text.append(String.format(Locale.ROOT, "Recorded filled volume: %.0f shares · $%,.2f bought + sold (turnover, not profit)\n", session.filledShares, session.tradedDollars));
        text.append(String.format("%-7s %-10s %-6s %-7s %s%n", "STOCK", "STATE", "OWNED", "TRADES", "LATEST DECISION"));
        session.legs.forEach((symbol, leg) -> text.append(String.format("%-7s %-10s %-6s %-7s %s%n", symbol, leg.phase, leg.owned, leg.completed, leg.message)));
        return text.toString();
    }
    private void acquire() throws Exception {
        if (lock != null && lock.isValid()) return;
        Files.createDirectories(legacy); Files.createDirectories(folder);
        channel = FileChannel.open(legacy.resolve("runner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { lock = channel.tryLock(); } catch (java.nio.channels.OverlappingFileLockException error) { lock = null; }
        if (lock == null) { channel.close(); throw new IOException("Another TALG window owns paper execution; use that window"); }
    }
    String prepareProfile() throws IOException {
        var profiles = new ModelProfiles(root); return profiles.enabled() ? profiles.snapshot("trade", profiles.selected("trade")) : null;
    }
    synchronized void arm(String date, Broker broker, String mode, Instant now) throws Exception { arm(date, broker, mode, now, null); }
    synchronized void arm(String date, Broker broker, String mode, Instant now, String preparedRevision) throws Exception {
        if (!mode.equals("paper")) throw new IOException("PAPER account required");
        LocalDate day = LocalDate.parse(date);
        if (day.isBefore(now.atZone(PaperTestRunner.EASTERN).toLocalDate()) || day.isAfter(now.atZone(PaperTestRunner.EASTERN).toLocalDate().plusDays(7))) throw new IOException("Choose a session within seven days");
        acquire();
        if (Files.exists(legacy.resolve("session.json"))) {
            String phase = PaperTestRunner.JSON.readTree(legacy.resolve("session.json").toFile()).path("phase").asText();
            if (!Set.of("COMPLETE", "IDLE").contains(phase)) throw new IOException("Stop and reconcile the old single-stock session first");
        }
        if (Files.exists(folder.resolve("session.json"))) {
            Session disk = PaperTestRunner.JSON.readValue(folder.resolve("session.json").toFile(), Session.class);
            if (!Set.of("COMPLETE", "IDLE").contains(disk.phase)) throw new IOException("Existing rapid session needs resume or review");
        }
        var profiles = new ModelProfiles(root);
        String revision = preparedRevision == null ? prepareProfile() : preparedRevision;
        var saved = revision == null ? null : profiles.revision(revision);
        if (saved != null && !saved.path("kind").asText().equals("trade")) throw new IOException("Expected a trading profile revision");
        var model = revision == null ? configuredModel() : PaperTestRunner.JSON.readValue(saved.path("contents").path("config").asText(), RapidPaperModel.class);
        String newsRevision = saved == null ? null : saved.path("dependencies").path("news").asText(null);
        var parsed = StrategyScript.parse(model.signal().script(), Set.of("previous_close", "predicted_return", "trading_cost", "prediction_rank"));
        if (revision != null && newsRevision == null && (parsed.reactToNews() || parsed.inputs().stream().anyMatch(i -> i.kind().equals("news")))) throw new IOException("Pin a News profile under Model → More → Profile links before arming");
        if (newsRevision != null) {
            profiles.revision(newsRevision);
            if (parsed.reactToNews()) {
                if (!Files.exists(profiles.revisionFolder(newsRevision).resolve("runtime/news-events.json"))) throw new IOException("Run the pinned News model before arming news reactions (More → Run linked news)");
                NewsModelEvents.read(root, newsRevision);
            }
        }
        var window = model.schedule().window(broker.calendar(date));
        if (window == null || !now.isBefore(window.entryEnd())) throw new IOException("No remaining entry window for this date and schedule");
        var statistics = PaperTestRunner.JSON.readValue(root.resolve(model.prediction().modelFile()).toFile(), StatisticalReturnModel.class);
        if (!statistics.symbols().containsAll(model.universe()) || !LocalDate.parse(statistics.trainedThrough()).isBefore(day)) throw new IOException("Predictor must cover the universe and predate the trading session");
        String accountId = broker.validate(date, model.universe()); Account account = broker.account();
        if (!accountId.equals(account.id())) throw new IOException("Paper account changed during arming");
        if (session.date.equals(date) && session.accountId.equals(accountId) && session.lossTriggered) throw new IOException("Today's loss trigger is latched; choose the next trading day");
        for (String symbol : model.universe()) if (account.positions().getOrDefault(symbol, BigDecimal.ZERO).signum() != 0 || account.openOrders().containsValue(symbol))
            throw new IOException("Existing position/order in " + symbol + "; reconcile before arming");
        archive(); var previous = session;
        session = new Session(); session.date = date; session.id = UUID.randomUUID().toString(); session.accountId = accountId; session.phase = "RUNNING";
        session.model = model; session.statistics = statistics; session.profileRevision = revision; session.newsProfileRevision = newsRevision;
        session.newsEventCursor = StrategyScript.parse(model.signal().script(), Set.of("previous_close", "predicted_return", "trading_cost", "prediction_rank")).reactToNews() ? NewsModelEvents.latest(root, session.newsProfileRevision) : 0;
        model.universe().forEach(s -> session.legs.put(s, new Leg())); session.message = "Armed statistical basket; saved model bricks are frozen for this session.";
        if (previous.date.equals(date) && previous.accountId.equals(accountId)) {
            // Re-arming a flat session must not erase today's loss or volume limits.
            session.gross = previous.gross; session.net = previous.net; session.entries = previous.entries;
            session.tradedDollars = previous.tradedDollars; session.filledShares = previous.filledShares;
            session.submissions = previous.submissions; session.completed = previous.completed; session.postTimes = new ArrayList<>(previous.postTimes);
            session.legs.forEach((symbol, leg) -> { var old = previous.legs.get(symbol); if (old != null) { leg.entries = old.entries; leg.completed = old.completed; leg.gross = old.gross; leg.net = old.net; } });
            session.message += " Today's prior P&L and counters retained.";
        }
        history.clear(); seedAttempted = false; save(); log("ARMED", Map.of("model", model, "statistics", statistics));
    }
    synchronized void requestStop() throws Exception {
        acquire(); session.stopRequested = true; session.userStopped = true; session.stopReason = "Stopped by user; automatic repeat disabled";
        session.phase = slots() == 0 ? "COMPLETE" : "STOPPING"; session.message = session.stopReason; save(); log("STOP_REQUESTED", Map.of());
    }
    synchronized boolean newsWakePending() {
        if (!active() || session.model == null || session.phase.equals("WAITING_NEXT")) return false;
        try {
            if (!StrategyScript.parse(session.model.signal().script(), Set.of("previous_close", "predicted_return", "trading_cost", "prediction_rank")).reactToNews()) return false;
            return NewsModelEvents.latest(root, session.newsProfileRevision) > session.newsEventCursor;
        } catch (Exception error) { return false; } // The normal account/risk scan remains scheduled.
    }
    synchronized void tick(Broker broker, String mode, Instant now) throws Exception {
        if (!active()) return; acquire();
        if (!mode.equals("paper")) { session.phase = "REVIEW"; session.message = "Paper connection changed; review positions"; save(); return; }
        if (session.phase.equals("WAITING_NEXT")) {
            if (now.atZone(PaperTestRunner.EASTERN).toLocalDate().isBefore(LocalDate.parse(session.nextSessionDate))) return;
            rollDay(broker, now);
        }
        var model = session.model; var symbols = model.universe();
        LocalDate date = now.atZone(PaperTestRunner.EASTERN).toLocalDate(); LocalTime time = now.atZone(PaperTestRunner.EASTERN).toLocalTime();
        var window = model.schedule().window(broker.calendar(session.date));
        if (date.isBefore(LocalDate.parse(session.date)) || window != null && now.isBefore(window.start())) return;
        boolean inWindow = window != null && window.open(now);
        if (window == null || !now.isBefore(window.entryEnd())) { session.stopRequested = true; if (session.stopReason.isBlank()) session.stopReason = "Scheduled entry window ended; closing positions"; }
        Account account;
        try { account = broker.account(); }
        catch (Exception error) { session.message = "Account check unavailable; no orders this scan: " + PaperTestRunner.safeError(error); save(); return; }
        if (!account.id().equals(session.accountId)) { session.phase = "REVIEW"; session.message = "Account mismatch"; save(); return; }
        session.scans++; session.lastScan = now.toString();
        Set<String> newsChanged = new HashSet<>();
        boolean reactToNews = StrategyScript.parse(model.signal().script(), Set.of("previous_close", "predicted_return", "trading_cost", "prediction_rank")).reactToNews();
        if (reactToNews) try {
            var stream = NewsModelEvents.read(root, session.newsProfileRevision);
            for (var event : NewsModelEvents.after(stream, session.newsEventCursor)) {
                double age = now.toEpochMilli() / 1000.0 - event.path("created_at").asDouble(Double.NaN);
                // Old backlog is not a new signal. Code declares required result freshness through News(...).
                double ttl = event.path("outputs").path("notify_ttl_seconds").asDouble(120);
                if (!Double.isFinite(ttl) || ttl < 1 || ttl > 86400 || !Double.isFinite(age) || age < -5 || age > ttl) continue;
                for (var target : event.path("symbols")) if (session.legs.containsKey(target.asText())) {
                    newsChanged.add(target.asText()); session.legs.get(target.asText()).newsTrigger = event;
                }
                log("NEWS_UPDATE", event);
            }
            session.newsEventCursor = Math.max(session.newsEventCursor, stream.path("latest").asLong());
        } catch (java.io.IOException error) { log("NEWS_UNAVAILABLE", Map.of("error", error.getMessage())); }

        // Settle pending orders before examining position snapshots; changed fills are checked next scan.
        Set<String> reconciled = new HashSet<>();
        for (var entry : session.legs.entrySet()) {
            String symbol = entry.getKey(); Leg leg = entry.getValue();
            if (leg.phase.equals("PENDING")) {
                try { reconcile(symbol, leg, broker, now, !inWindow); } catch (Exception error) { review(symbol, leg, error); }
                reconciled.add(symbol);
            }
        }
        Map<String, Market> markets = Map.of();
        boolean marketAvailable = inWindow && (model.schedule().extendedHours() || account.open());
        if (marketAvailable) {
            if (!seedAttempted) {
                seedAttempted = true;
                try {
                    var seed = broker.seed(symbols, now);
                    for (var e : seed.entrySet()) for (var bar : e.getValue()) observe(e.getKey(), bar, now);
                    log("WARMUP", Map.of("symbols", seed.keySet(), "issues", broker.seedIssues()));
                } catch (Exception error) { log("WARMUP_UNAVAILABLE", Map.of("error", PaperTestRunner.safeError(error))); }
            }
            try {
                markets = broker.market(symbols);
                for (var e : markets.entrySet()) observe(e.getKey(), e.getValue().bar(), now);
                if (!session.stopRequested) session.message = "Scanning statistical signals; entries require fresh prices and available capacity.";
            } catch (Exception error) { session.message = "Market data unavailable; no new submissions: " + PaperTestRunner.safeError(error); }
        }
        double marked = session.net;
        Map<String, BidQuoteSignal.Move> quoteMoves = new HashMap<>();
        for (var e : session.legs.entrySet()) {
            var market = markets.get(e.getKey());
            if (market == null) continue;
            var move = BidQuoteSignal.between(e.getValue().previousQuote, market.quote(), now);
            quoteMoves.put(e.getKey(), move);
            if (move.newer()) e.getValue().previousQuote = market.quote();
        }
        for (var e : session.legs.entrySet()) {
            Market m = markets.get(e.getKey()); Leg leg = e.getValue();
            if (leg.owned.signum() > 0 && fresh(m, now)) marked += (m.quote().bid() - leg.entry) * leg.owned.doubleValue();
        }
        if (marked <= -model.portfolio().lossDollars()) { session.stopRequested = true; session.lossTriggered = true; session.stopReason = "Daily loss trigger reached; exiting positions"; session.message = session.stopReason; }
        if (model.portfolio().maxEntries() > 0 && session.entries >= model.portfolio().maxEntries()) { session.stopRequested = true; session.stopReason = "Daily entry cap reached"; }
        Map<String, Double> predictions = new HashMap<>(), ranks = new HashMap<>();
        for (String symbol : symbols) {
            var bars = history.getOrDefault(symbol, List.of());
            if (bars.size() >= 8 && fresh(markets.get(symbol), now)
                    && Duration.between(Instant.parse(bars.getLast().time()).plusSeconds(60), now).getSeconds() <= 90
                    && Duration.between(Instant.parse(bars.getFirst().time()), Instant.parse(bars.getLast().time())).getSeconds() == 7 * 60)
                predictions.put(symbol, session.statistics.predict(bars));
        }
        var ranked = predictions.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().thenComparing(Map.Entry.comparingByKey())).toList();
        for (int i = 0; i < ranked.size(); i++) ranks.put(ranked.get(i).getKey(), ranked.size() < 2 ? 0.0 : (double) i / (ranked.size() - 1));
        var newsInputs = new NewsModelInputs(root, model.signal().script(), now, session.newsProfileRevision);
        Map<String, StrategyScript.Evaluation> evaluations = new HashMap<>();
        // Retain the model's values even when portfolio/execution gates prevent an entry.
        // This is a readout of the same frozen model; it never authorizes an order.
        for (String symbol : predictions.keySet()) {
            Leg leg = session.legs.get(symbol); var bars = history.get(symbol);
            try {
                var newsResult = newsInputs.forSymbol(model.signal().script(), symbol);
                var evaluation = model.evaluate(bars, predictions.get(symbol), tradingCost(markets.get(symbol)), ranks.get(symbol), newsResult.values());
                evaluations.put(symbol, evaluation); leg.newsModelInputs = newsResult.provenance();
                leg.modelValues = new LinkedHashMap<>(evaluation.values());
                leg.modelEvaluatedAt = now.toString(); leg.modelDecision = evaluation.side(); leg.modelError = "";
                leg.retainedBars = List.copyOf(bars);
            } catch (IllegalArgumentException error) { leg.modelError = error.getMessage(); leg.modelValues = new LinkedHashMap<>(); leg.modelDecision = "unavailable"; leg.modelEvaluatedAt = now.toString(); leg.newsModelInputs = null; }
        }
        // Exit processing has priority over entries and the submission budget reserves capacity for exits.
        for (String symbol : symbols) {
            Leg leg = session.legs.get(symbol);
            if (reconciled.contains(symbol) || Set.of("PENDING", "REVIEW", "SUBMITTING").contains(leg.phase)) continue;
            boolean otherOrder = account.openOrders().entrySet().stream().anyMatch(e -> e.getValue().equals(symbol) && !e.getKey().equals(leg.orderId));
            if (otherOrder || account.positions().getOrDefault(symbol, BigDecimal.ZERO).compareTo(leg.owned) != 0) {
                review(symbol, leg, new IOException("Position/open order differs from this session; manual review required")); continue;
            }
            if (leg.owned.signum() == 0) continue;
            Market market = markets.get(symbol);
            if (!marketAvailable || !fresh(market, now)) { leg.message = "Holding: waiting for scheduled hours and fresh quote to exit"; continue; }
            double bid = market.quote().bid(); leg.high = Math.max(leg.high, bid);
            if (leg.high >= leg.entry + leg.risk) leg.stop = Math.max(leg.stop, leg.high - model.exits().trailR() * leg.risk);
            long held = Duration.between(Instant.parse(leg.entryAt), now).getSeconds();
            var bars = history.getOrDefault(symbol, List.of()); boolean modelExit = false;
            if (evaluations.containsKey(symbol)) modelExit = evaluations.get(symbol).sell();
            var move = quoteMoves.get(symbol); var bidRules = model.signal().bidRules();
            boolean bidExit = bidRules.enabled() && move != null && bidRules.exit(move);
            if (bidRules.enabled()) modelExit = modelExit && move != null && move.changed() && move.comparable();
            boolean timedExit = model.exits().holdSeconds() > 0 && held >= model.exits().holdSeconds();
            if (session.stopRequested || leg.forceExit || bidExit || modelExit || timedExit || bid <= leg.stop || bid >= leg.target) {
                leg.orderReason = session.stopRequested ? session.stopReason : leg.forceExit ? "Incomplete fill: close remainder"
                        : bid <= leg.stop ? "Bid hit stop/trailing stop" : bid >= leg.target ? "Bid hit target"
                        : bidExit ? "Bid exit expression passed: " + String.format(Locale.ROOT, "%.2f bp; size imbalance %.3f", move.bidChangeBps(), move.imbalance())
                        : modelExit ? "Statistical sell signal" : "Timed holding exit";
                if (bidRules.enabled()) logQuoteDecision(symbol, market, move, "sell", leg.orderReason);
                if (postBudget(now, false)) try { submit(symbol, leg, "sell", limit(market, false), broker, now); } catch (Exception error) { review(symbol, leg, error); }
            } else leg.message = String.format(Locale.ROOT, "Holding · bid %.2f · stop %.2f · target %.2f%s", bid, leg.stop, leg.target, bidRules.enabled() ? " · no timed exit" : "");
        }
        // Rotate scan priority across the basket so earlier symbols do not monopolize slots.
        double buyingPower = account.buyingPower();
        for (int index = 0; index < symbols.size(); index++) {
            String symbol = symbols.get((index + session.scans) % symbols.size()); Leg leg = session.legs.get(symbol); Market market = markets.get(symbol);
            if (Set.of("PENDING", "REVIEW", "SUBMITTING").contains(leg.phase) || leg.owned.signum() != 0 || reconciled.contains(symbol)) continue;
            if (session.stopRequested) { leg.message = "New entries stopped"; continue; }
            if (reactToNews && !newsChanged.contains(symbol)) { leg.message = "Waiting for a news-model update for " + symbol; continue; }
            if (!marketAvailable || !fresh(market, now)) { leg.message = "Scheduled scan active; waiting for fresh IEX quote"; continue; }
            var move = quoteMoves.get(symbol); boolean bidDriven = model.signal().bidRules().enabled();
            if (bidDriven && (move == null || !model.signal().bidRules().enter(move))) {
                leg.message = move == null || !move.comparable() ? "Waiting for two fresh bid observations"
                        : !move.hasSizes() ? "Waiting for valid displayed bid/ask sizes"
                        : String.format(Locale.ROOT, "No bid entry: change %.2fbp · size imbalance %.3f", move.bidChangeBps(), move.imbalance());
                continue;
            }
            observe(symbol, market.bar(), now);
            if (!leg.nextAt.isBlank() && now.isBefore(Instant.parse(leg.nextAt))) { leg.message = model.execution().cooldownSeconds() + "-second re-entry cooldown"; continue; }
            var bars = history.getOrDefault(symbol, List.of());
            if (bars.size() < model.signal().warmupBars()) { leg.message = "Warming up: " + bars.size() + "/8 closed minute bars"; continue; }
            if (!predictions.containsKey(symbol)) { leg.message = "Waiting for eight fresh, consecutive minute bars"; continue; }
            var last = bars.getLast();
            String signalKey = reactToNews ? "news:" + leg.newsTrigger.path("id").asText() : bidDriven ? market.quote().time() : last.time();
            if (signalKey.equals(leg.entrySignalAt)) continue;
            boolean newSignal = !signalKey.equals(leg.signalAt);
            leg.signalAt = signalKey;
            double prediction = predictions.get(symbol), cost = tradingCost(market);
            var evaluation = evaluations.get(symbol);
            if (evaluation == null) { leg.message = "Model unavailable: " + leg.modelError; continue; }
            boolean signal = evaluation.buy();
            double risk = Math.max(last.high() - last.low(), last.close() * model.exits().minimumRiskFraction());
            if (newSignal) log("SIGNAL", Map.of("symbol", symbol, "enter", signal, "bar", last, "risk", risk, "features", StatisticalReturnModel.features(bars), "values", evaluation.values()));
            if (!signal) { leg.message = String.format(Locale.ROOT, "Forecast %.2fbp; cost %.2fbp; no entry", prediction * 10000, cost * 10000); continue; }
            if (Duration.between(Instant.parse(last.time()).plusSeconds(60), now).getSeconds() > 90
                    || Duration.between(Instant.parse(bars.getFirst().time()), Instant.parse(last.time())).getSeconds() != 7 * 60) { leg.message = "Skipped stale/gapped bars"; continue; }
            double spread = market.quote().ask() - market.quote().bid();
            BigDecimal limit = limit(market, true);
            if (spread / market.quote().ask() > model.execution().maxSpreadFraction() || risk > last.close() * model.exits().maximumRiskFraction() || limit.doubleValue() > last.close() + risk * model.execution().maxChaseR()) { leg.message = "Skipped spread, volatility or price chase"; continue; }
            BigDecimal quantity = entryQuantity(model.sizing(), limit, buyingPower, exposure(markets, now));
            if (limit.doubleValue() < 5 || quantity.signum() == 0) { leg.message = "Waiting for entry dollars, total exposure or buying power"; continue; }
            if (slots() >= model.portfolio().maxPositions() || model.portfolio().maxEntries() > 0 && session.entries >= model.portfolio().maxEntries()
                    || model.portfolio().maxEntriesPerStock() > 0 && leg.entries >= model.portfolio().maxEntriesPerStock() || !postBudget(now, true)) { leg.message = "Waiting for portfolio/order capacity"; continue; }
            leg.risk = risk;
            leg.requested = quantity; leg.entrySignalAt = signalKey;
            leg.orderReason = bidDriven ? "Bid entry expression and statistical entry passed" : "Statistical entry passed";
            if (bidDriven) logQuoteDecision(symbol, market, move, "buy", leg.orderReason);
            try { submit(symbol, leg, "buy", limit, broker, now); buyingPower -= limit.multiply(quantity).doubleValue(); } catch (Exception error) { review(symbol, leg, error); }
        }
        if (session.stopRequested) {
            session.phase = "STOPPING";
            if (slots() == 0) {
                boolean review = session.legs.values().stream().anyMatch(l -> l.phase.equals("REVIEW"));
                session.phase = review ? "REVIEW" : "COMPLETE"; session.message = session.stopReason.isBlank() ? "Session complete and flat" : session.stopReason;
                if (!review && !session.userStopped && model.schedule().repeatTradingDays()) {
                    session.nextSessionDate = broker.nextTradingDate(session.date); session.phase = "WAITING_NEXT";
                    session.message += "; flat, waiting for " + session.nextSessionDate;
                }
            } else if (!inWindow && session.legs.values().stream().noneMatch(l -> l.phase.equals("PENDING"))) {
                session.phase = "REVIEW"; session.message = "Trading window ended with unresolved positions/orders; automatic repeat blocked";
            }
        }
        save();
    }
    private void archive() throws IOException {
        if (session.id.isBlank()) return;
        Path archive = folder.resolve("sessions"); Files.createDirectories(archive);
        PaperTestRunner.JSON.writerWithDefaultPrettyPrinter().writeValue(archive.resolve(session.date + "-" + session.id + ".json").toFile(), session);
    }
    private void logQuoteDecision(String symbol, Market market, BidQuoteSignal.Move move, String side, String reason) throws IOException {
        var quote = market.quote();
        var detail = PaperTestRunner.JSON.createObjectNode().put("symbol", symbol).put("side", side).put("reason", reason)
                .put("quoteAt", quote.time()).put("bid", quote.bid()).put("ask", quote.ask());
        if (Double.isFinite(quote.bidSize())) detail.put("bidSize", quote.bidSize());
        if (Double.isFinite(quote.askSize())) detail.put("askSize", quote.askSize());
        if (move != null && move.comparable()) detail.put("bidChangeBps", move.bidChangeBps());
        if (move != null && move.hasSizes()) detail.put("bidImbalance", move.imbalance());
        log("QUOTE_DECISION", detail);
    }
    private void rollDay(Broker broker, Instant now) throws Exception {
        String day = broker.nextTradingDate(now.atZone(PaperTestRunner.EASTERN).toLocalDate().minusDays(1).toString());
        var account = broker.account();
        if (!account.id().equals(session.accountId)) throw new IOException("Account changed while waiting for next trading day");
        for (String symbol : session.model.universe()) if (account.positions().getOrDefault(symbol, BigDecimal.ZERO).signum() != 0 || account.openOrders().containsValue(symbol))
            throw new IOException("Next day blocked by existing position/order: " + symbol);
        archive(); var previous = session; session = new Session(); session.date = day; session.id = UUID.randomUUID().toString();
        session.accountId = previous.accountId; session.model = previous.model; session.statistics = previous.statistics;
        session.profileRevision = previous.profileRevision; session.newsProfileRevision = previous.newsProfileRevision; session.newsEventCursor = previous.newsEventCursor;
        session.phase = "RUNNING"; session.model.universe().forEach(s -> session.legs.put(s, new Leg())); history.clear(); seedAttempted = false;
        session.message = "New trading day; daily counters reset. Saved model and schedule retained."; save(); log("DAY_STARTED", Map.of("previousSession", previous.id, "date", day));
    }
    private void observe(String symbol, IntradayMomentum.Bar bar, Instant now) {
        if (bar == null || Instant.parse(bar.time()).plusSeconds(65).isAfter(now)) return;
        List<IntradayMomentum.Bar> bars = history.computeIfAbsent(symbol, ignored -> new ArrayList<>());
        if (bars.isEmpty() || Instant.parse(bar.time()).isAfter(Instant.parse(bars.getLast().time()))) { bars.add(bar); if (bars.size() > 8) bars.removeFirst(); }
    }
    static boolean fresh(Market market, Instant now) {
        if (market == null || market.quote() == null) return false;
        var q = market.quote(); long age;
        try { age = Duration.between(Instant.parse(q.time()), now).getSeconds(); } catch (Exception error) { return false; }
        return Double.isFinite(q.bid() + q.ask()) && q.bid() > 0 && q.ask() >= q.bid() && age >= -5 && age <= 30;
    }
    static BigDecimal limit(Market market, boolean buy) { return BigDecimal.valueOf(buy ? market.quote().ask() + .01 : market.quote().bid() - .01).setScale(2, buy ? RoundingMode.CEILING : RoundingMode.FLOOR); }
    private static double tradingCost(Market market) { return .0004 + .02 / market.quote().ask() + (market.quote().ask() - market.quote().bid()) / market.quote().ask(); }
    static BigDecimal entryQuantity(RapidPaperModel.Sizing sizing, BigDecimal limit, double buyingPower, double exposure) {
        double budget = Math.min(sizing.maxEntryDollars(), Math.min(buyingPower, sizing.maxExposureDollars() - exposure));
        if (!Double.isFinite(budget) || budget <= 0 || limit.signum() <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(budget).divide(limit, 0, RoundingMode.FLOOR).min(BigDecimal.valueOf(sizing.shares())).max(BigDecimal.ZERO);
    }
    private double exposure(Map<String, Market> markets, Instant now) {
        double total = 0;
        for (var entry : session.legs.entrySet()) {
            var leg = entry.getValue(); var market = markets.get(entry.getKey());
            if (leg.side.equals("buy") && Set.of("PENDING", "SUBMITTING", "REVIEW").contains(leg.phase)) {
                // Reserve the whole pending buy, including any not-yet-reconciled partial fill.
                total += leg.requested.doubleValue() * (leg.orderLimit > 0 ? leg.orderLimit : session.model.sizing().maxEntryDollars());
            } else total += leg.owned.doubleValue() * Math.max(leg.entry, fresh(market, now) ? market.quote().ask() : leg.entry);
        }
        return total;
    }
    int slots() { return (int) session.legs.values().stream().filter(l -> l.owned.signum() > 0 || Set.of("PENDING", "SUBMITTING").contains(l.phase) || l.phase.equals("REVIEW") && !l.clientId.isBlank()).count(); }
    private boolean postBudget(Instant now, boolean entry) {
        session.postTimes.removeIf(t -> Instant.parse(t).isBefore(now.minusSeconds(60)));
        int maximum = session.model.execution().postsPerMinute();
        return session.postTimes.size() < (entry ? maximum - session.model.portfolio().maxPositions() : maximum);
    }
    private void submit(String symbol, Leg leg, String side, BigDecimal limit, Broker broker, Instant now) throws Exception {
        leg.side = side; leg.requested = side.equals("buy") ? leg.requested : leg.owned; leg.clientId = "talg-rapid-" + UUID.randomUUID();
        leg.orderLimit = limit.doubleValue(); leg.checkedAt = "";
        leg.orderId = ""; leg.sentAt = now.toString(); leg.cancelRequested = false; leg.phase = "SUBMITTING";
        session.submissions++; session.postTimes.add(now.toString());
        if (side.equals("buy")) { leg.entries++; session.entries++; } else leg.exitAttempts++;
        save(); log("SUBMIT_INTENT", Map.of("symbol", symbol, "side", side, "qty", leg.requested, "limit", limit, "clientId", leg.clientId, "reason", leg.orderReason));
        leg.orderId = broker.submit(symbol, side, leg.requested, limit, leg.clientId, session.model.schedule().extendedHours());
        leg.phase = "PENDING"; leg.message = side + " accepted; awaiting fill"; save(); log("ORDER_ACCEPTED", Map.of("symbol", symbol, "orderId", leg.orderId));
    }
    private void reconcile(String symbol, Leg leg, Broker broker, Instant now, boolean closed) throws Exception {
        if (!closed && !session.stopRequested && !leg.checkedAt.isBlank() && Duration.between(Instant.parse(leg.checkedAt), now).getSeconds() < 10) return;
        var order = broker.order(leg.orderId);
        leg.checkedAt = now.toString();
        if (!order.id().equals(leg.orderId) || !order.clientId().equals(leg.clientId) || !order.symbol().equals(symbol) || !order.side().equals(leg.side)
                || order.filled().signum() < 0 || order.filled().compareTo(leg.requested) > 0) throw new IOException("Order identity/quantity mismatch");
        if (!Set.of("filled", "canceled", "expired", "rejected").contains(order.status())) {
            if (!leg.cancelRequested && (closed || Duration.between(Instant.parse(leg.sentAt), now).getSeconds() >= 30 || session.stopRequested && leg.side.equals("buy"))) {
                leg.cancelRequested = true; save(); broker.cancel(leg.orderId); log("CANCEL_REQUESTED", Map.of("symbol", symbol, "orderId", leg.orderId));
            }
            leg.message = order.status() + " filled=" + order.filled(); return;
        }
        log("ORDER_TERMINAL", Map.of("symbol", symbol, "order", order));
        if (order.filled().signum() > 0 && (!Double.isFinite(order.averagePrice()) || order.averagePrice() <= 0)) throw new IOException("Missing fill price");
        session.filledShares += order.filled().doubleValue(); session.tradedDollars += order.filled().doubleValue() * order.averagePrice();
        if (leg.side.equals("buy")) {
            leg.owned = order.filled(); leg.entry = order.averagePrice(); leg.entryAt = now.toString(); leg.high = leg.entry;
            leg.stop = leg.entry - leg.risk; leg.target = leg.entry + session.model.exits().targetR() * leg.risk; leg.exitAttempts = 0;
            leg.forceExit = !order.status().equals("filled");
        } else {
            double qty = order.filled().doubleValue(), gross = (order.averagePrice() - leg.entry) * qty;
            double net = qty == 0 ? 0 : gross - (order.averagePrice() + leg.entry) * qty * .0002 - .02 * qty;
            leg.owned = leg.owned.subtract(order.filled()); leg.gross += gross; leg.net += net; session.gross += gross; session.net += net;
            if (leg.owned.signum() == 0) { leg.completed++; session.completed++; }
            else { leg.forceExit = true; if (leg.exitAttempts >= 3) throw new IOException("Three terminal exit attempts; remaining quantity " + leg.owned); }
            log("PNL", Map.of("symbol", symbol, "gross", gross, "estimatedNet", net, "remaining", leg.owned));
        }
        leg.phase = leg.owned.signum() > 0 ? "HOLDING" : "WATCHING"; leg.message = "Terminal fill recorded";
        leg.orderId = ""; leg.clientId = ""; leg.nextAt = now.plusSeconds(session.model.execution().cooldownSeconds()).toString(); save();
    }
    private void review(String symbol, Leg leg, Exception error) throws Exception { leg.phase = "REVIEW"; leg.message = PaperTestRunner.safeError(error); session.stopRequested = true; session.message = symbol + " requires review; new entries stopped, other owned positions will exit"; save(); log("REVIEW", Map.of("symbol", symbol, "reason", leg.message)); }
    private void save() throws IOException {
        Files.createDirectories(folder); Path temp = Files.createTempFile(folder, "session-", ".tmp");
        try { PaperTestRunner.JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), session);
            try { Files.move(temp, folder.resolve("session.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temp, folder.resolve("session.json"), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private void log(String event, Object detail) throws IOException { Files.writeString(folder.resolve("events-" + session.date + ".jsonl"), PaperTestRunner.JSON.writeValueAsString(Map.of("at", Instant.now().toString(), "sessionId", session.id, "event", event, "detail", detail)) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
    @Override public synchronized void close() throws IOException { if (lock != null) lock.release(); if (channel != null) channel.close(); }

    static final class Alpaca implements Broker {
        private static TradingSchedule.Day cachedDay;
        private static Instant calendarChecked = Instant.EPOCH;
        private final PaperTestRunner.AlpacaBroker orders;
        private final IntradayMarketData data;
        private final Map<String, String> warmupIssues = new LinkedHashMap<>();
        Alpaca(AlpacaSettings settings) {
            if (!settings.feed().equals("iex")) throw new IllegalArgumentException("Rapid pilot uses the free IEX feed; select IEX in Settings");
            orders = new PaperTestRunner.AlpacaBroker(settings); data = new IntradayMarketData(settings);
        }
        public String validate(String date, List<String> symbols) throws Exception {
            var account = orders.account();
            if (!calendar(date).tradingDay()) throw new IOException("The date is not a broker trading day");
            var assets = orders.call("/v2/assets?status=active&asset_class=us_equity", "GET", "", false);
            if (!assets.isArray()) throw new IOException("Incomplete broker asset list");
            Set<String> tradable = new HashSet<>();
            for (var asset : assets) if (asset.path("tradable").asBoolean() && asset.path("status").asText().equals("active")) tradable.add(asset.path("symbol").asText());
            for (String symbol : symbols) if (!tradable.contains(symbol)) throw new IOException(symbol + " is not tradable");
            return account.path("id").asText();
        }
        public Account account() throws Exception {
            var account = orders.account(); var clock = orders.call("/v2/clock", "GET", "", false);
            var positions = orders.call("/v2/positions", "GET", "", false); var open = orders.call("/v2/orders?status=open&limit=500", "GET", "", false);
            if (!positions.isArray() || !open.isArray() || open.size() >= 500) throw new IOException("Incomplete positions/order snapshot");
            Map<String, BigDecimal> quantities = new HashMap<>(); Map<String, String> ids = new HashMap<>();
            for (var p : positions) quantities.put(p.path("symbol").asText(), new BigDecimal(p.path("qty").asText()));
            for (var o : open) ids.put(o.path("id").asText(), o.path("symbol").asText());
            return new Account(account.path("id").asText(), clock.path("is_open").asBoolean(), Double.parseDouble(account.path("buying_power").asText()), quantities, ids);
        }
        public Map<String, Market> market(List<String> symbols) throws Exception {
            var snapshots = data.get("/v2/stocks/snapshots?symbols=" + String.join(",", symbols) + "&feed=iex"); Map<String, Market> result = new HashMap<>();
            for (String symbol : symbols) {
                var snapshot = snapshots.path(symbol); var q = snapshot.path("latestQuote"); var b = snapshot.path("minuteBar");
                IntradayMomentum.Bar bar = null;
                try {
                    Instant.parse(b.path("t").asText()); double o = b.path("o").asDouble(Double.NaN), h = b.path("h").asDouble(Double.NaN), l = b.path("l").asDouble(Double.NaN), c = b.path("c").asDouble(Double.NaN), v = b.path("v").asDouble(Double.NaN);
                    if (Double.isFinite(o + h + l + c + v) && l > 0 && h >= Math.max(o, c) && l <= Math.min(o, c) && v > 0)
                        bar = new IntradayMomentum.Bar(b.path("t").asText(), o, h, l, c, v, b.path("vw").asDouble(Double.NaN));
                } catch (Exception ignored) { /* Missing symbols never become fabricated observations. */ }
                result.put(symbol, new Market(new IntradayMarketData.Quote(q.path("bp").asDouble(Double.NaN), q.path("ap").asDouble(Double.NaN), q.path("t").asText(), q.path("bs").asDouble(Double.NaN), q.path("as").asDouble(Double.NaN)), bar));
            }
            return result;
        }
        public TradingSchedule.Day calendar(String date) throws Exception {
            synchronized (Alpaca.class) {
                if (cachedDay != null && cachedDay.date().equals(date) && Instant.now().isBefore(calendarChecked.plusSeconds(60))) return cachedDay;
            }
            LocalDate.parse(date); var days = orders.call("/v2/calendar?start=" + date + "&end=" + date, "GET", "", false);
            if (!days.isArray() || days.size() > 1) throw new IOException("Invalid broker calendar response");
            if (!days.isEmpty() && !days.get(0).path("date").asText().equals(date)) throw new IOException("Broker calendar date mismatch");
            var day = days.isEmpty() ? new TradingSchedule.Day(date, false, "09:30", "16:00") : new TradingSchedule.Day(date, true, days.get(0).path("open").asText(), days.get(0).path("close").asText());
            synchronized (Alpaca.class) { cachedDay = day; calendarChecked = Instant.now(); }
            return day;
        }
        public String nextTradingDate(String afterDate) throws Exception {
            LocalDate start = LocalDate.parse(afterDate).plusDays(1);
            var days = orders.call("/v2/calendar?start=" + start + "&end=" + start.plusDays(20), "GET", "", false);
            if (!days.isArray() || days.isEmpty()) throw new IOException("No next trading day returned by broker");
            String date = days.get(0).path("date").asText(); if (LocalDate.parse(date).isBefore(start)) throw new IOException("Invalid next trading date"); return date;
        }
        public String submit(String symbol, String side, BigDecimal qty, BigDecimal limit, String client) throws Exception { return submit(symbol, side, qty, limit, client, false); }
        public String submit(String symbol, String side, BigDecimal qty, BigDecimal limit, String client, boolean extended) throws Exception {
            var clock = orders.call("/v2/clock", "GET", "", false); Instant now = Instant.parse(clock.path("timestamp").asText());
            var day = calendar(now.atZone(PaperTestRunner.EASTERN).toLocalDate().toString());
            var hours = extended ? TradingSchedule.fullDay() : new TradingSchedule("09:30", "16:00", false, false, 10, 1);
            var window = hours.window(day);
            if (window == null || !window.open(now) || !extended && !clock.path("is_open").asBoolean()) throw new IOException("Outside eligible broker trading hours; order not queued");
            String body = orderBody(symbol, side, qty, limit, client, extended);
            var response = orders.call("/v2/orders", "POST", body, false); String id = response.path("id").asText();
            if (id.isBlank() || !client.equals(response.path("client_order_id").asText())) throw new IOException("Uncertain paper order acknowledgment; reconcile client ID");
            UUID.fromString(id); return id;
        }
        static String orderBody(String symbol, String side, BigDecimal qty, BigDecimal limit, String client, boolean extended) {
            if (!symbol.matches("[A-Z]{1,5}") || !Set.of("buy", "sell").contains(side) || qty.signum() <= 0 || qty.compareTo(BigDecimal.valueOf(100)) > 0 || limit.signum() <= 0
                    || side.equals("buy") && (qty.stripTrailingZeros().scale() > 0 || qty.multiply(limit).compareTo(BigDecimal.valueOf(5000)) > 0))
                throw new IllegalArgumentException("Invalid bounded paper order");
            return PaperTestRunner.JSON.createObjectNode().put("symbol", symbol).put("side", side).put("qty", qty.toPlainString())
                    .put("type", "limit").put("limit_price", limit.toPlainString()).put("time_in_force", "day")
                    .put("extended_hours", extended).put("client_order_id", client).toString();
        }
        public Map<String, List<IntradayMomentum.Bar>> seed(List<String> symbols, Instant now) throws Exception {
            Map<String, List<IntradayMomentum.Bar>> result = new HashMap<>();
            warmupIssues.clear();
            // One short history request per symbol only at startup/restart; subsequent scans are batched.
            for (String symbol : symbols) {
                try { result.put(symbol, data.bars(symbol, now.minusSeconds(20 * 60), now.minusSeconds(5), true)); }
                catch (Exception error) { warmupIssues.put(symbol, PaperTestRunner.safeError(error)); }
            }
            return result;
        }
        public Map<String, String> seedIssues() { return Map.copyOf(warmupIssues); }
        public PaperTestRunner.Order order(String id) throws Exception { return orders.order(id); }
        public void cancel(String id) throws Exception { orders.cancel(id); }
    }
}
