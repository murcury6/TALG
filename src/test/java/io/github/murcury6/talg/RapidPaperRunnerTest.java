package io.github.murcury6.talg;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RapidPaperRunnerTest {
    @TempDir Path root;
    static final Instant START = Instant.parse("2026-09-23T14:00:10Z");
    final class Fake implements RapidPaperRunner.Broker {
        Instant now = START; boolean uncertain, stale, regularOpen = true; int posts, maxPositions;
        boolean usedExtended, seedFails; int seedCalls, marketCalls; double buyingPower = 100000, quoteBid = 100, bidSize = 100, askSize = 100;
        public TradingSchedule.Day calendar(String date) { var day = LocalDate.parse(date); return new TradingSchedule.Day(date, day.getDayOfWeek().getValue() < 6, "09:30", "16:00"); }
        public String nextTradingDate(String afterDate) { var day = LocalDate.parse(afterDate).plusDays(1); while (day.getDayOfWeek().getValue() > 5) day = day.plusDays(1); return day.toString(); }
        Map<String, BigDecimal> positions = new HashMap<>(); Map<String, PaperTestRunner.Order> orders = new HashMap<>(); List<Instant> postTimes = new ArrayList<>();
        public String validate(String date, List<String> symbols) { return "paper-account"; }
        public RapidPaperRunner.Account account() { return new RapidPaperRunner.Account("paper-account", regularOpen, buyingPower, Map.copyOf(positions), Map.of()); }
        public Map<String, RapidPaperRunner.Market> market(List<String> symbols) {
            marketCalls++;
            Map<String, RapidPaperRunner.Market> result = new HashMap<>();
            for (String symbol : symbols) result.put(symbol, new RapidPaperRunner.Market(new IntradayMarketData.Quote(quoteBid, quoteBid + .01, (stale ? now.minusSeconds(90) : now).toString(), bidSize, askSize), bar(now.truncatedTo(ChronoUnit.MINUTES).minusSeconds(60))));
            return result;
        }
        public Map<String, List<IntradayMomentum.Bar>> seed(List<String> symbols, Instant at) {
            seedCalls++;
            if (seedFails) throw new IllegalStateException("History endpoint unavailable");
            Map<String, List<IntradayMomentum.Bar>> result = new HashMap<>();
            for (String symbol : symbols) { List<IntradayMomentum.Bar> bars = new ArrayList<>(); for (int i = 8; i >= 1; i--) bars.add(bar(now.truncatedTo(ChronoUnit.MINUTES).minusSeconds(i * 60L))); result.put(symbol, bars); }
            return result;
        }
        public String submit(String symbol, String side, BigDecimal qty, BigDecimal limit, String client) throws Exception {
            var disk = PaperTestRunner.JSON.readTree(root.resolve("work/rapid-paper/session.json").toFile());
            assertEquals("SUBMITTING", disk.path("legs").path(symbol).path("phase").asText());
            posts++; postTimes.add(now); if (uncertain) throw new java.io.IOException("Response lost");
            String id = UUID.randomUUID().toString(); positions.put(symbol, positions.getOrDefault(symbol, BigDecimal.ZERO).add(side.equals("buy") ? qty : qty.negate()));
            maxPositions = Math.max(maxPositions, (int) positions.values().stream().filter(q -> q.signum() != 0).count());
            orders.put(id, new PaperTestRunner.Order(id, client, symbol, side, "filled", qty, limit.doubleValue())); return id;
        }
        public PaperTestRunner.Order order(String id) { return orders.get(id); }
        public String submit(String symbol, String side, BigDecimal qty, BigDecimal limit, String client, boolean extended) throws Exception { usedExtended = extended; return submit(symbol, side, qty, limit, client); }
        public void cancel(String id) {}
    }
    static IntradayMomentum.Bar bar(Instant at) { return new IntradayMomentum.Bar(at.toString(), 99.98, 100.02, 99.97, 100, 1000, 100); }
    void artifact() throws Exception {
        Files.createDirectories(root.resolve("work/models")); double[] scales = new double[7]; Arrays.fill(scales, 1); double[] weights = new double[8]; weights[0] = .001;
        var model = new StatisticalReturnModel(1, "2026-09-17", RapidPaperRunner.SYMBOLS, StatisticalReturnModel.FEATURES, new double[7], scales, weights, Map.of());
        PaperTestRunner.JSON.writeValue(root.resolve("work/models/rapid-return-v1.json").toFile(), model);
        Files.createDirectories(root.resolve("work/strategies")); var defaults = RapidPaperModel.defaults();
        var legacy = new RapidPaperModel(defaults.version(), defaults.universe(), defaults.prediction(), defaults.signal(), defaults.sizing(), defaults.exits(),
                new RapidPaperModel.Portfolio(5, 200, 20, 50), defaults.execution(), TradingSchedule.legacy());
        PaperTestRunner.JSON.writeValue(root.resolve("work/strategies/rapid-paper.json").toFile(), legacy);
    }
    @Test void volumeReplayCompletesOneHundredRoundTripsWithinAllLimits() throws Exception {
        artifact(); Fake broker = new Fake();
        try (var runner = new RapidPaperRunner(root)) {
            runner.arm("2026-09-23", broker, "paper", START);
            for (int i = 0; i < 900; i++) { broker.now = START.plusSeconds(i * 10L); runner.tick(broker, "paper", broker.now); }
            runner.requestStop(); for (int i = 900; i < 905; i++) { broker.now = START.plusSeconds(i * 10L); runner.tick(broker, "paper", broker.now); }
            assertEquals(100, runner.state().completed); assertEquals(200, broker.posts); assertTrue(broker.maxPositions <= 5);
            assertTrue(runner.state().legs.values().stream().allMatch(l -> l.entries <= 20 && l.owned.signum() == 0)); assertEquals("COMPLETE", runner.state().phase);
            for (Instant time : broker.postTimes) assertTrue(broker.postTimes.stream().filter(t -> !t.isBefore(time.minusSeconds(60)) && !t.isAfter(time)).count() <= 12);
            Files.createDirectories(Path.of("work/rapid-paper/research"));
            PaperTestRunner.JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("work/rapid-paper/research/execution-load-test.json").toFile(),
                    Map.of("environment", "deterministic fake broker, not actual exchange fills", "completedRoundTrips", runner.state().completed,
                            "orderSubmissions", broker.posts, "maximumConcurrentPositions", broker.maxPositions, "virtualMinutes", 150, "limitsPassed", true));
        }
    }
    @Test void uncertainPostIsNeverRetriedAndReservesItsSlotAcrossRestart() throws Exception {
        artifact(); Fake broker = new Fake(); broker.uncertain = true;
        try (var runner = new RapidPaperRunner(root)) { runner.arm("2026-09-23", broker, "paper", START); runner.tick(broker, "paper", START); assertEquals(1, broker.posts); assertEquals(1, runner.slots()); }
        try (var runner = new RapidPaperRunner(root)) { broker.now = START.plusSeconds(10); runner.tick(broker, "paper", broker.now); assertEquals(1, broker.posts); assertTrue(runner.state().stopRequested); }
    }
    @Test void newsEventsWakeOnlyAffectedEntriesAndDoNotReplay() throws Exception {
        artifact();
        Path config = root.resolve("work/strategies/rapid-paper.json");
        var model = (com.fasterxml.jackson.databind.node.ObjectNode) PaperTestRunner.JSON.readTree(config.toFile());
        var signal = (com.fasterxml.jackson.databind.node.ObjectNode) model.path("signal");
        signal.put("script", "react news\n" + signal.path("script").asText().replace("buy prediction_rank >= 0.75", "buy 1").replace("sell prediction_rank < 0.5", "sell 0"));
        PaperTestRunner.JSON.writeValue(config.toFile(), model);
        Fake broker = new Fake(); Path stream = root.resolve("work/news-tensor/news-events.json"); Files.createDirectories(stream.getParent());
        try (var runner = new RapidPaperRunner(root)) {
            runner.arm("2026-09-23", broker, "paper", START); runner.tick(broker,"paper", START);
            assertEquals(0, broker.posts);
            Files.writeString(stream, "{\"latest\":1,\"events\":[{\"sequence\":1,\"id\":\"news-1\",\"created_at\":" + START.getEpochSecond() + ",\"symbols\":[\"AAPL\"],\"outputs\":{}}]}");
            assertTrue(runner.newsWakePending());
            broker.now = START.plusSeconds(1); runner.tick(broker,"paper",broker.now);
            assertEquals(1, broker.posts); assertTrue(broker.positions.containsKey("AAPL")); assertFalse(runner.newsWakePending());
            assertEquals("news-1", runner.state().legs.get("AAPL").newsTrigger.path("id").asText());
            broker.now = START.plusSeconds(10); runner.tick(broker,"paper",broker.now); assertEquals(1,broker.posts);
            runner.requestStop(); broker.now = START.plusSeconds(20); runner.tick(broker,"paper",broker.now);
            assertEquals(2,broker.posts); // Independent exit does not require another news event.
        }
    }
    @Test void missingNewsStopsEntriesButCannotBlockIndependentExits() throws Exception {
        artifact();
        Path config = root.resolve("work/strategies/rapid-paper.json");
        var model = (com.fasterxml.jackson.databind.node.ObjectNode) PaperTestRunner.JSON.readTree(config.toFile());
        var signal = (com.fasterxml.jackson.databind.node.ObjectNode) model.path("signal");
        signal.put("script", "input news_rating News(rating)\n" + signal.path("script").asText());
        PaperTestRunner.JSON.writeValue(config.toFile(), model);
        String source = "output rating = 1\n";
        Files.writeString(root.resolve("work/models/news-rating.talg"), source);
        String version = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var snapshot = PaperTestRunner.JSON.createObjectNode().put("model_version", version).put("evaluated_at", START.getEpochSecond());
        var symbols = snapshot.putObject("symbols");
        RapidPaperRunner.SYMBOLS.forEach(symbol -> symbols.putObject(symbol).putObject("outputs").put("rating", 1));
        Path ratings = root.resolve("work/news-tensor/symbol-ratings.json"); Files.createDirectories(ratings.getParent());
        PaperTestRunner.JSON.writeValue(ratings.toFile(), snapshot);
        Fake broker = new Fake();
        try (var runner = new RapidPaperRunner(root)) {
            runner.arm("2026-09-23", broker,"paper",START); runner.tick(broker,"paper",START); assertEquals(5,broker.posts);
            Files.delete(ratings); runner.requestStop();
            for (int i=1; i<=3; i++) { broker.now = START.plusSeconds(i*10L); runner.tick(broker,"paper",broker.now); }
            assertEquals(10,broker.posts); assertEquals("COMPLETE",runner.state().phase);
            assertTrue(runner.state().legs.values().stream().allMatch(leg -> leg.owned.signum() == 0));
        }
    }
    @Test void staleQuotesAndLiveModeNeverSendOrders() throws Exception {
        artifact(); Fake broker = new Fake(); broker.stale = true;
        try (var runner = new RapidPaperRunner(root)) {
            assertThrows(Exception.class, () -> runner.arm("2026-09-23", broker, "live", START));
            runner.arm("2026-09-23", broker, "paper", START); runner.tick(broker, "paper", START); assertEquals(0, broker.posts);
            broker.stale = false; runner.tick(broker, "live", START.plusSeconds(10)); assertEquals(0, broker.posts); assertEquals("REVIEW", runner.state().phase);
        }
    }
    @Test void modelBricksArePersistedAndFrozenWhileArmed() throws Exception {
        artifact(); Fake broker = new Fake();
        try (var runner = new RapidPaperRunner(root)) {
            String costOnly = runner.modelSource().replace("buy prediction_rank >= 0.75", "buy expected_edge > 1"); runner.saveModel(costOnly);
            runner.arm("2026-09-23", broker, "paper", START); runner.tick(broker, "paper", START); assertEquals(0, broker.posts);
            assertThrows(Exception.class, () -> runner.saveModel(costOnly));
            assertTrue(runner.state().model.signal().script().contains("buy expected_edge > 1"));
        }
    }
    @Test void pendingFillsSurviveRestartWithoutDuplicateOrdersAndStopFlattens() throws Exception {
        artifact(); Fake broker = new Fake();
        try (var runner = new RapidPaperRunner(root)) { runner.arm("2026-09-23", broker, "paper", START); runner.tick(broker, "paper", START); assertEquals(5, broker.posts); }
        try (var runner = new RapidPaperRunner(root)) {
            runner.requestStop(); for (int i = 1; i <= 4; i++) { broker.now = START.plusSeconds(i * 10); runner.tick(broker, "paper", broker.now); }
            assertEquals(10, broker.posts); assertEquals(5, runner.state().completed); assertEquals("COMPLETE", runner.state().phase);
        }
    }
    void fullSchedule() throws Exception { PaperTestRunner.JSON.writeValue(root.resolve("work/strategies/rapid-paper.json").toFile(), RapidPaperModel.defaults()); }
    @Test void premarketAndPostmarketTradeWhileRegularClockIsClosed() throws Exception {
        artifact(); fullSchedule(); Fake broker = new Fake(); broker.regularOpen = false; broker.now = Instant.parse("2026-09-23T08:30:10Z");
        try (var runner = new RapidPaperRunner(root)) {
            runner.arm("2026-09-23", broker, "paper", broker.now); runner.tick(broker, "paper", broker.now);
            assertEquals(5, broker.posts); assertTrue(broker.usedExtended); runner.requestStop();
            for (int i = 1; i <= 3; i++) { broker.now = Instant.parse("2026-09-23T08:30:10Z").plusSeconds(i * 10); runner.tick(broker, "paper", broker.now); }
            assertEquals("COMPLETE", runner.state().phase);
            broker.now = Instant.parse("2026-09-23T21:30:10Z"); runner.arm("2026-09-23", broker, "paper", broker.now); runner.tick(broker, "paper", broker.now);
            assertEquals(10, runner.state().entries); assertTrue(broker.usedExtended);
        }
    }
    @Test void zeroCapsDoNotStopAtTwoHundredOrTwentyPerStock() throws Exception {
        artifact(); fullSchedule(); Fake broker = new Fake();
        try (var runner = new RapidPaperRunner(root)) {
            runner.arm("2026-09-23", broker, "paper", START); runner.state().entries = 200;
            runner.state().legs.values().forEach(leg -> leg.entries = 20); runner.tick(broker, "paper", START);
            assertEquals(205, runner.state().entries); assertEquals(5, broker.posts); assertFalse(runner.state().stopRequested);
        }
    }
    @Test void newPinnedEventStreamMustBeInitializedBeforeArming() throws Exception {
        artifact(); var config = (com.fasterxml.jackson.databind.node.ObjectNode) PaperTestRunner.JSON.readTree(root.resolve("work/strategies/rapid-paper.json").toFile());
        var signal = (com.fasterxml.jackson.databind.node.ObjectNode)config.path("signal"); signal.put("script", "react news\n" + signal.path("script").asText());
        PaperTestRunner.JSON.writeValue(root.resolve("work/strategies/rapid-paper.json").toFile(), config);
        Files.writeString(root.resolve("work/models/news-tensor.json"), ModelFilesTest.NEWS);
        Files.writeString(root.resolve("work/models/news-rating.talg"), "output score = 1");
        var profiles = new ModelProfiles(root); profiles.initialize(); profiles.snapshot("trade", "default");
        String ref = profiles.newsReference("trade", "default"); Fake broker = new Fake();
        try (var runner = new RapidPaperRunner(root)) {
            assertThrows(java.io.IOException.class, () -> runner.arm("2026-09-23", broker, "paper", START));
            assertEquals("IDLE", runner.state().phase); assertEquals(0, broker.posts);
            Path stream = profiles.revisionFolder(ref).resolve("runtime/news-events.json"); Files.createDirectories(stream.getParent());
            var payload = PaperTestRunner.JSON.createObjectNode().put("profile_revision", ref).put("latest", 5); payload.putArray("events");
            PaperTestRunner.JSON.writeValue(stream.toFile(), payload);
            runner.arm("2026-09-23", broker, "paper", START); assertEquals(5, runner.state().newsEventCursor);
        }
    }
    @Test void armedProfileAndNestedNewsLocksSurviveEditingRestartAndDailyRepeat() throws Exception {
        artifact(); fullSchedule();
        Files.writeString(root.resolve("work/models/news-tensor.json"), ModelFilesTest.NEWS);
        Files.writeString(root.resolve("work/models/news-rating.talg"), "output score = 1");
        var profiles = new ModelProfiles(root); profiles.initialize();
        String chosen = profiles.cloneProfile("trade", "default", "Morning"); profiles.select("trade", chosen);
        Fake broker = new Fake(); broker.now = Instant.parse("2026-09-25T14:00:10Z"); String tradeRevision, newsRevision;
        try (var runner = new RapidPaperRunner(root)) {
            runner.arm("2026-09-25", broker, "paper", broker.now);
            tradeRevision = runner.state().profileRevision; newsRevision = runner.state().newsProfileRevision;
            assertNotNull(tradeRevision); assertNotNull(newsRevision);
            assertEquals(chosen, profiles.revision(tradeRevision).path("profile").asText());
            var files = new ModelFiles(root); files.profile(false, chosen);
            String old = files.read(false); String changed = old.replace("let expected_edge =", "# future profile edit\\nlet expected_edge =");
            files.save(false, old, changed);
            assertFalse(runner.state().model.signal().script().contains("future profile edit"));
            profiles.select("trade", "default"); Files.writeString(root.resolve("work/models/news-rating.talg"), "output score = 8");
            profiles.snapshot("news", "default");
            broker.now = Instant.parse("2026-09-26T00:00:00Z"); runner.tick(broker, "paper", broker.now);
            assertEquals("WAITING_NEXT", runner.state().phase);
        }
        try (var runner = new RapidPaperRunner(root)) {
            assertEquals(tradeRevision, runner.state().profileRevision); assertEquals(newsRevision, runner.state().newsProfileRevision);
            broker.now = Instant.parse("2026-09-28T08:30:10Z"); broker.regularOpen = false; runner.tick(broker, "paper", broker.now);
            assertEquals("2026-09-28", runner.state().date);
            assertEquals(tradeRevision, runner.state().profileRevision); assertEquals(newsRevision, runner.state().newsProfileRevision);
            assertEquals("output score = 1", profiles.revision(newsRevision).path("contents").path("source").asText());
        }
    }
    @Test void fullDayRepeatsAcrossWeekendButStopDisablesRepeat() throws Exception {
        artifact(); fullSchedule(); Fake broker = new Fake(); broker.now = Instant.parse("2026-09-25T14:00:10Z");
        try (var runner = new RapidPaperRunner(root)) {
            runner.arm("2026-09-25", broker, "paper", broker.now);
            broker.now = Instant.parse("2026-09-26T00:00:00Z"); runner.tick(broker, "paper", broker.now);
            assertEquals("WAITING_NEXT", runner.state().phase); assertEquals("2026-09-28", runner.state().nextSessionDate); assertEquals(0, broker.posts);
        }
        try (var runner = new RapidPaperRunner(root)) {
            broker.now = Instant.parse("2026-09-27T15:00:00Z"); runner.tick(broker, "paper", broker.now); assertEquals("WAITING_NEXT", runner.state().phase);
            broker.now = Instant.parse("2026-09-28T08:30:10Z"); broker.regularOpen = false; runner.tick(broker, "paper", broker.now);
            assertEquals("2026-09-28", runner.state().date); assertEquals(5, broker.posts);
            runner.requestStop(); for (int i = 1; i <= 3; i++) { broker.now = broker.now.plusSeconds(10); runner.tick(broker, "paper", broker.now); }
            assertEquals("COMPLETE", runner.state().phase); assertTrue(runner.state().userStopped);
            assertEquals(1, Files.list(root.resolve("work/rapid-paper/sessions")).count());
        }
    }
    @Test void regularOnlyScheduleNeverPostsAfterHoursAndHolidayCannotArm() throws Exception {
        artifact(); Fake broker = new Fake(); broker.regularOpen = false;
        try (var runner = new RapidPaperRunner(root)) {
            assertThrows(Exception.class, () -> runner.arm("2026-09-26", broker, "paper", START));
            assertThrows(Exception.class, () -> runner.arm("2026-09-23", broker, "paper", Instant.parse("2026-09-23T21:00:00Z")));
            runner.arm("2026-09-23", broker, "paper", START); runner.tick(broker, "paper", START); assertEquals(0, broker.posts);
        }
    }
    @Test void rearmSameDayDoesNotResetDailyLossAndRepeatResetsNextDay() throws Exception {
        artifact(); fullSchedule(); Fake broker = new Fake();
        try (var runner = new RapidPaperRunner(root)) {
            runner.arm("2026-09-23", broker, "paper", START); runner.state().net = -50; runner.requestStop();
            runner.arm("2026-09-23", broker, "paper", START); assertEquals(-50, runner.state().net);
            runner.tick(broker, "paper", START); assertEquals(0, broker.posts); assertEquals("WAITING_NEXT", runner.state().phase);
            broker.now = Instant.parse("2026-09-24T08:30:10Z"); runner.tick(broker, "paper", broker.now);
            assertEquals(0, runner.state().net); assertEquals(5, broker.posts);
        }
    }
    void fastConfig(double exposure) throws Exception {
        artifact(); var tree = (com.fasterxml.jackson.databind.node.ObjectNode) PaperTestRunner.JSON.valueToTree(RapidPaperModel.fastPaper());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("sizing")).put("maxExposureDollars", exposure);
        PaperTestRunner.JSON.writeValue(root.resolve("work/strategies/rapid-paper.json").toFile(), tree);
    }
    @Test void dollarSizingRespectsShareEntryExposureAndBuyingPowerCaps() {
        var sizing = RapidPaperModel.fastPaper().sizing(); var price = new BigDecimal("100.02");
        assertEquals(new BigDecimal("49"), RapidPaperRunner.entryQuantity(sizing, price, 100000, 0));
        assertEquals(new BigDecimal("1"), RapidPaperRunner.entryQuantity(sizing, price, 150, 0));
        assertEquals(new BigDecimal("2"), RapidPaperRunner.entryQuantity(sizing, price, 100000, 49700));
        assertEquals(BigDecimal.ZERO, RapidPaperRunner.entryQuantity(sizing, price, 100000, 50000));
        assertEquals(new BigDecimal("100"), RapidPaperRunner.entryQuantity(sizing, new BigDecimal("5"), 100000, 0));
    }
    @Test void pendingBuysReserveDollarsBeforeBrokerSnapshotCatchesUp() throws Exception {
        fastConfig(12000); Fake broker = new Fake();
        try (var runner = new RapidPaperRunner(root)) {
            runner.arm("2026-09-23", broker, "paper", START); runner.tick(broker, "paper", START);
            double reserved = runner.state().legs.values().stream().mapToDouble(l -> l.requested.doubleValue() * l.orderLimit).sum();
            assertEquals(3, broker.posts); assertTrue(reserved <= 12000 && reserved > 11900);
        }
    }
    @Test void capacityBlockedSignalCanRetryWithinSameBarWithoutDuplicateEntry() throws Exception {
        artifact(); Fake broker = new Fake();
        try (var runner = new RapidPaperRunner(root)) {
            runner.arm("2026-09-23", broker, "paper", START);
            for (int i = 0; i < 12; i++) runner.state().postTimes.add(START.minusSeconds(59).toString());
            runner.tick(broker, "paper", START); assertEquals(0, broker.posts);
            broker.now = START.plusSeconds(5); runner.tick(broker, "paper", broker.now); assertEquals(5, broker.posts);
            broker.now = START.plusSeconds(10); runner.tick(broker, "paper", broker.now); assertEquals(5, broker.posts);
        }
    }
    @Test void partialFillsAndRestartSellOnlyRemainingQuantityAndCountTurnoverOnce() throws Exception {
        fastConfig(5000); Fake broker = new Fake(); String symbol;
        try (var runner = new RapidPaperRunner(root)) {
            runner.arm("2026-09-23", broker, "paper", START); runner.tick(broker, "paper", START);
            var entry = runner.state().legs.entrySet().stream().filter(e -> e.getValue().phase.equals("PENDING")).findFirst().orElseThrow(); symbol = entry.getKey(); var leg = entry.getValue();
            broker.orders.put(leg.orderId, new PaperTestRunner.Order(leg.orderId, leg.clientId, symbol, "buy", "canceled", new BigDecimal("7"), 100));
            broker.positions.put(symbol, new BigDecimal("7"));
        }
        try (var runner = new RapidPaperRunner(root)) {
            runner.requestStop(); broker.now = START.plusSeconds(5); runner.tick(broker, "paper", broker.now);
            assertEquals(new BigDecimal("7"), runner.state().legs.get(symbol).owned);
            broker.now = START.plusSeconds(10); runner.tick(broker, "paper", broker.now); var leg = runner.state().legs.get(symbol);
            assertEquals(new BigDecimal("7"), leg.requested);
            broker.orders.put(leg.orderId, new PaperTestRunner.Order(leg.orderId, leg.clientId, symbol, "sell", "canceled", new BigDecimal("2"), 101)); broker.positions.put(symbol, new BigDecimal("5"));
            broker.now = START.plusSeconds(15); runner.tick(broker, "paper", broker.now);
            assertEquals(new BigDecimal("5"), leg.owned);
        }
        try (var runner = new RapidPaperRunner(root)) {
            broker.now = START.plusSeconds(20); runner.tick(broker, "paper", broker.now); assertEquals(new BigDecimal("5"), runner.state().legs.get(symbol).requested);
            broker.now = START.plusSeconds(25); runner.tick(broker, "paper", broker.now);
            assertEquals("COMPLETE", runner.state().phase); assertEquals(14, runner.state().filledShares); assertEquals(1401.95, runner.state().tradedDollars, .0001);
        }
    }
    @Test void fastPresetProducesMultiShareTurnoverWithinRollingOrderBudget() throws Exception {
        fastConfig(50000); Fake broker = new Fake();
        try (var runner = new RapidPaperRunner(root)) {
            runner.arm("2026-09-23", broker, "paper", START);
            for (int i = 0; i < 120; i++) { broker.now = START.plusSeconds(i * 5L); runner.tick(broker, "paper", broker.now); }
            runner.requestStop(); for (int i = 120; i < 140; i++) { broker.now = START.plusSeconds(i * 5L); runner.tick(broker, "paper", broker.now); }
            assertEquals("COMPLETE", runner.state().phase); assertTrue(runner.state().completed >= 40);
            assertTrue(runner.state().tradedDollars > 400000); assertTrue(broker.maxPositions <= 10);
            assertTrue(broker.orders.values().stream().allMatch(o -> o.filled().compareTo(BigDecimal.ONE) > 0));
            for (Instant time : broker.postTimes) assertTrue(broker.postTimes.stream().filter(t -> !t.isBefore(time.minusSeconds(60)) && !t.isAfter(time)).count() <= 24);
        }
    }
    @Test void bidModeRequiresChangedBidAndDoesNotExitOnElapsedHoldingTime() throws Exception {
        fastConfig(5000);
        var path = root.resolve("work/strategies/rapid-paper.json");
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) PaperTestRunner.JSON.readTree(path.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("signal")).set("bidRules", PaperTestRunner.JSON.valueToTree(RapidPaperModel.BidRules.active()));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("exits")).put("holdSeconds", 0); PaperTestRunner.JSON.writeValue(path.toFile(), tree);
        Fake broker = new Fake();
        try (var runner = new RapidPaperRunner(root)) {
            runner.arm("2026-09-23", broker, "paper", START); runner.tick(broker, "paper", START); assertEquals(0, broker.posts);
            broker.now = START.plusSeconds(5); runner.tick(broker, "paper", broker.now); assertEquals(0, broker.posts);
            broker.now = START.plusSeconds(10); broker.quoteBid = 100.01; broker.bidSize = 300; runner.tick(broker, "paper", broker.now); assertEquals(1, broker.posts);
            broker.now = START.plusSeconds(15); runner.tick(broker, "paper", broker.now); assertEquals(1, broker.posts);
            broker.now = START.plusSeconds(135); runner.tick(broker, "paper", broker.now); assertEquals(1, broker.posts, "No forced 60-second exit");
        }
        try (var runner = new RapidPaperRunner(root)) {
            broker.now = START.plusSeconds(140); runner.tick(broker, "paper", broker.now); assertEquals(1, broker.posts, "Unchanged quote after restart creates no strategy trade");
            broker.now = START.plusSeconds(145); broker.quoteBid = 99.98; runner.tick(broker, "paper", broker.now); assertEquals(2, broker.posts);
            assertTrue(runner.state().legs.values().stream().anyMatch(l -> l.orderReason.startsWith("Bid exit expression passed")));
            broker.now = START.plusSeconds(150); runner.tick(broker, "paper", broker.now); assertEquals(1, runner.state().completed);
        }
    }
    @Test void lossTriggerStaysLatchedEvenAfterLiquidationImprovesRealizedPnl() throws Exception {
        artifact(); fullSchedule(); Fake broker = new Fake();
        try (var runner = new RapidPaperRunner(root)) {
            runner.arm("2026-09-23", broker, "paper", START); runner.state().net = -50; runner.tick(broker, "paper", START);
            runner.state().net = -20; runner.requestStop();
            assertThrows(Exception.class, () -> runner.arm("2026-09-23", broker, "paper", START));
        }
        try (var runner = new RapidPaperRunner(root)) {
            assertThrows(Exception.class, () -> runner.arm("2026-09-23", broker, "paper", START));
            runner.arm("2026-09-24", broker, "paper", START); assertEquals(0, runner.state().net); assertFalse(runner.state().lossTriggered);
        }
    }
    @Test void unavailableSeedDoesNotBlockSnapshotsOrRepeatRequestsAndCanWarmNaturally() throws Exception {
        artifact(); Fake broker = new Fake(); broker.seedFails = true;
        try (var runner = new RapidPaperRunner(root)) {
            runner.arm("2026-09-23", broker, "paper", START);
            for (int i = 0; i < 8; i++) { broker.now = START.plusSeconds(i * 60L); runner.tick(broker, "paper", broker.now); }
            assertEquals(1, broker.seedCalls); assertEquals(8, broker.marketCalls);
            assertEquals(5, broker.posts, "Fresh snapshots should build enough real bars to trade");
        }
    }
    @Test void expandedMixedUniverseRemainsBoundedAndRequiresModelCoverage() throws Exception {
        artifact(); Fake broker = new Fake();
        var symbols = new ArrayList<>(RapidPaperRunner.SYMBOLS);
        symbols.addAll(List.of("SPY", "QQQ", "IWM", "DIA", "VTI", "XLK", "XLF", "XLE", "XLV", "XLI", "XLP", "XLY", "XLU", "XLB", "XLRE", "XLC", "SMH", "SOXX", "XBI", "KRE", "EEM", "EFA", "GLD", "SLV", "TLT", "HYG", "UVXY"));
        var configPath = root.resolve("work/strategies/rapid-paper.json");
        var config = (com.fasterxml.jackson.databind.node.ObjectNode) PaperTestRunner.JSON.readTree(configPath.toFile());
        config.set("universe", PaperTestRunner.JSON.valueToTree(symbols)); PaperTestRunner.JSON.writeValue(configPath.toFile(), config);
        try (var runner = new RapidPaperRunner(root)) { assertThrows(Exception.class, () -> runner.arm("2026-09-23", broker, "paper", START)); }
        var fittedPath = root.resolve("work/models/rapid-return-v1.json");
        var fitted = (com.fasterxml.jackson.databind.node.ObjectNode) PaperTestRunner.JSON.readTree(fittedPath.toFile());
        fitted.set("symbols", PaperTestRunner.JSON.valueToTree(symbols)); PaperTestRunner.JSON.writeValue(fittedPath.toFile(), fitted);
        try (var runner = new RapidPaperRunner(root)) {
            runner.arm("2026-09-23", broker, "paper", START); runner.tick(broker, "paper", START);
            assertEquals(47, runner.state().legs.size()); assertEquals(5, broker.posts); assertEquals(5, runner.slots());
        }
    }
}
