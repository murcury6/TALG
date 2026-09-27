package io.github.murcury6.talg;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperTestRunnerTest {
    @TempDir Path root;
    static final PaperTestRunner.Plan PLAN = new PaperTestRunner.Plan("2026-09-23", "SPY", 300, 2, 1000);
    static final Instant START = PLAN.start();
    static final class Fake implements PaperTestRunner.Broker {
        BigDecimal position = BigDecimal.ZERO;
        boolean open = true, other, uncertain;
        int submits, cancels;
        String lastSide = "", lastClient = "", lastId = "", status = "new";
        BigDecimal filled = BigDecimal.ZERO, quantity;
        double bid = 700, ask = 700.01, execution = 700;
        IntradayMomentum.Signal signal = new IntradayMomentum.Signal(false, false, 700, 1, 0, 0, 699, 2, "2026-09-23T13:46:00Z", "Waiting");
        Path statePath;
        public String validatePlan(PaperTestRunner.Plan plan) { return "paper-account"; }
        public PaperTestRunner.Check check(String symbol, String own) { return new PaperTestRunner.Check("paper-account", open, position, other); }
        public BigDecimal limit(String symbol, String side, BigDecimal qty, double cap, Instant now) { return BigDecimal.valueOf(700); }
        public String submit(String symbol, String side, BigDecimal qty, BigDecimal limit, String client) throws Exception {
            assertEquals("SUBMITTING", PaperTestRunner.JSON.readTree(statePath.toFile()).path("phase").asText());
            submits++; lastSide = side; lastClient = client; lastId = UUID.randomUUID().toString(); quantity = qty;
            filled = BigDecimal.ZERO; status = "new";
            if (uncertain) throw new java.io.IOException("simulated lost response");
            return lastId;
        }
        public PaperTestRunner.Order order(String id) { return new PaperTestRunner.Order(id, lastClient, "SPY", lastSide, status, filled, execution); }
        public IntradayMomentum.Signal signal(Instant now) { return signal; }
        public IntradayMarketData.Quote quote() { return new IntradayMarketData.Quote(bid, ask, "2026-09-23T13:46:00Z"); }
        public void cancel(String id) { cancels++; }
        void fill() { status = "filled"; filled = quantity; position = lastSide.equals("buy") ? position.add(filled) : position.subtract(filled); }
    }
    Fake broker() { var fake = new Fake(); fake.statePath = root.resolve("work/paper-test/session.json"); return fake; }
    @Test void waitsForScheduleAndMarketAndRefusesLiveMode() throws Exception {
        try (var runner = new PaperTestRunner(root)) {
            var broker = broker();
            assertThrows(Exception.class, () -> runner.arm(PLAN, broker, "live", START.minusSeconds(3600)));
            runner.arm(PLAN, broker, "paper", START.minusSeconds(3600));
            runner.tick(broker, "paper", START.minusSeconds(1)); assertEquals(0, broker.submits);
            broker.open = false; runner.tick(broker, "paper", START); assertEquals(0, broker.submits);
            broker.open = true; runner.tick(broker, "live", START.plusSeconds(30));
            assertEquals(0, broker.submits); assertEquals("PAUSED", runner.state().phase);
        }
    }
    @Test void fillsCooldownAndRoundTripCapControlOrdersWithoutCatchUpBursts() throws Exception {
        try (var runner = new PaperTestRunner(root)) {
            var broker = broker(); runner.arm(PLAN, broker, "paper", START.minusSeconds(3600));
            Instant now = START;
            for (int trip = 0; trip < 2; trip++) {
                runner.tick(broker, "paper", now); assertEquals("buy", broker.lastSide);
                runner.tick(broker, "paper", now.plusSeconds(15)); assertEquals(2 * trip + 1, broker.submits);
                broker.fill(); now = now.plusSeconds(30); runner.tick(broker, "paper", now);
                runner.tick(broker, "paper", now.plusSeconds(299)); assertEquals(2 * trip + 1, broker.submits);
                now = now.plusSeconds(300); runner.tick(broker, "paper", now); assertEquals("sell", broker.lastSide);
                broker.fill(); now = now.plusSeconds(15); runner.tick(broker, "paper", now);
                now = now.plusSeconds(300);
            }
            runner.tick(broker, "paper", now.plusSeconds(1800));
            assertEquals(4, broker.submits); assertEquals(2, runner.state().completed);
            assertEquals("COMPLETE", runner.state().phase); assertEquals(0, broker.position.signum());
        }
    }
    @Test void uncertainSubmissionRemainsPausedAcrossRestartAndCannotBeRearmed() throws Exception {
        var broker = broker(); broker.uncertain = true;
        try (var runner = new PaperTestRunner(root)) {
            runner.arm(PLAN, broker, "paper", START.minusSeconds(3600)); runner.tick(broker, "paper", START);
            assertEquals("PAUSED", runner.state().phase); assertFalse(runner.state().clientId.isBlank());
            runner.tick(broker, "paper", START.plusSeconds(600)); assertEquals(1, broker.submits);
        }
        try (var restarted = new PaperTestRunner(root)) {
            restarted.tick(broker, "paper", START.plusSeconds(1200)); assertEquals(1, broker.submits);
            assertThrows(Exception.class, () -> restarted.arm(PLAN, broker, "paper", START.plusSeconds(1200)));
        }
    }
    @Test void twoWindowsCannotRunTheSamePaperSession() throws Exception {
        var broker = broker();
        try (var first = new PaperTestRunner(root)) {
            first.arm(PLAN, broker, "paper", START.minusSeconds(3600));
            try (var second = new PaperTestRunner(root)) {
                assertThrows(Exception.class, () -> second.tick(broker, "paper", START));
            }
            first.tick(broker, "paper", START); assertEquals(1, broker.submits);
        }
    }
    @Test void timedOutPartialEntryIsCanceledThenOnlyFilledQuantityIsExited() throws Exception {
        try (var runner = new PaperTestRunner(root)) {
            var broker = broker(); runner.arm(PLAN, broker, "paper", START.minusSeconds(3600)); runner.tick(broker, "paper", START);
            broker.filled = new BigDecimal("0.4"); broker.position = broker.filled; broker.status = "partially_filled";
            runner.tick(broker, "paper", START.plusSeconds(121)); assertEquals(1, broker.cancels);
            runner.tick(broker, "paper", START.plusSeconds(140)); assertEquals(1, broker.cancels); assertEquals(1, broker.submits);
            broker.status = "canceled"; runner.tick(broker, "paper", START.plusSeconds(150));
            runner.tick(broker, "paper", START.plusSeconds(165)); assertEquals("sell", broker.lastSide); assertEquals(new BigDecimal("0.4"), broker.quantity);
            broker.fill(); runner.tick(broker, "paper", START.plusSeconds(180));
            assertEquals("COMPLETE", runner.state().phase); assertEquals(0, broker.position.signum());
        }
    }
    @Test void existingOrChangedPositionsAndOtherOrdersBlockTheTest() throws Exception {
        var broker = broker(); broker.position = BigDecimal.ONE;
        try (var runner = new PaperTestRunner(root)) {
            assertThrows(Exception.class, () -> runner.arm(PLAN, broker, "paper", START.minusSeconds(3600)));
            broker.position = BigDecimal.ZERO; runner.arm(PLAN, broker, "paper", START.minusSeconds(3600));
            broker.other = true; runner.tick(broker, "paper", START);
            assertEquals("PAUSED", runner.state().phase); assertEquals(0, broker.submits);
        }
    }
    @Test void stopBeforeOpenAndExpiredEmptySessionsNeverSubmit() throws Exception {
        try (var runner = new PaperTestRunner(root)) {
            var broker = broker(); runner.arm(PLAN, broker, "paper", START.minusSeconds(3600)); runner.requestStop();
            runner.tick(broker, "paper", START); assertEquals(0, broker.submits); assertEquals("COMPLETE", runner.state().phase);
        }
    }
    static final PaperTestRunner.Plan SIGNAL_PLAN = new PaperTestRunner.Plan("2026-09-23", "SPY", 300, 2, 1000, "opening-range-v1");
    static final Instant SIGNAL_TIME = Instant.parse("2026-09-23T13:46:05Z");
    static IntradayMomentum.Signal entrySignal(Instant now, double risk) {
        return new IntradayMomentum.Signal(true, false, 700, 1, 0, 0, 699, risk, now.minusSeconds(5).toString(), "Breakout");
    }
    @Test void noSignalMeansNoTradeAndClosedEntryWindowCompletesFlat() throws Exception {
        try (var runner = new PaperTestRunner(root)) {
            var broker = broker(); runner.arm(SIGNAL_PLAN, broker, "paper", START.minusSeconds(3600));
            runner.tick(broker, "paper", SIGNAL_TIME); assertEquals(0, broker.submits);
            runner.tick(broker, "paper", Instant.parse("2026-09-23T15:00:00Z"));
            assertEquals("COMPLETE", runner.state().phase); assertEquals(0, broker.submits);
        }
    }
    @Test void profitExitIgnoresEntryCooldownAndRecordsActualFillPnl() throws Exception {
        try (var runner = new PaperTestRunner(root)) {
            var broker = broker(); broker.signal = entrySignal(SIGNAL_TIME, 2);
            runner.arm(SIGNAL_PLAN, broker, "paper", START.minusSeconds(3600)); runner.tick(broker, "paper", SIGNAL_TIME);
            assertEquals(1, broker.submits); broker.fill(); runner.tick(broker, "paper", SIGNAL_TIME.plusSeconds(15));
            assertEquals(698, runner.state().stopPrice); assertEquals(704, runner.state().targetPrice);
            broker.bid = 704; broker.ask = 704.01; broker.execution = 704;
            runner.tick(broker, "paper", SIGNAL_TIME.plusSeconds(30)); assertEquals("sell", broker.lastSide);
            broker.fill(); runner.tick(broker, "paper", SIGNAL_TIME.plusSeconds(45));
            assertEquals(4, runner.state().realizedGross, .0001); assertEquals(3.6992, runner.state().estimatedNet, .0001);
            runner.tick(broker, "paper", SIGNAL_TIME.plusSeconds(60)); assertEquals(2, broker.submits);
        }
    }
    @Test void stopAndRiskLevelsSurviveRestartWithoutDuplicateEntry() throws Exception {
        var broker = broker(); broker.signal = entrySignal(SIGNAL_TIME, 2);
        try (var runner = new PaperTestRunner(root)) {
            runner.arm(SIGNAL_PLAN, broker, "paper", START.minusSeconds(3600)); runner.tick(broker, "paper", SIGNAL_TIME);
            broker.fill(); runner.tick(broker, "paper", SIGNAL_TIME.plusSeconds(15));
        }
        try (var runner = new PaperTestRunner(root)) {
            broker.bid = 697.50; broker.execution = 697.50;
            runner.tick(broker, "paper", SIGNAL_TIME.plusSeconds(30)); assertEquals("sell", broker.lastSide);
            assertEquals(2, broker.submits); broker.fill(); runner.tick(broker, "paper", SIGNAL_TIME.plusSeconds(45));
            assertEquals(-2.5, runner.state().realizedGross, .0001); assertEquals(1, runner.state().consecutiveLosses);
        }
    }
    @Test void wideSpreadsStaleSignalsAndExcessRiskNeverSubmit() throws Exception {
        try (var runner = new PaperTestRunner(root)) {
            var broker = broker(); runner.arm(SIGNAL_PLAN, broker, "paper", START.minusSeconds(3600));
            broker.signal = entrySignal(SIGNAL_TIME.minusSeconds(180), 2);
            runner.tick(broker, "paper", SIGNAL_TIME); assertEquals(0, broker.submits);
            broker.signal = entrySignal(SIGNAL_TIME, 2); broker.ask = 702;
            runner.tick(broker, "paper", SIGNAL_TIME); assertEquals(0, broker.submits);
            broker.ask = 700.01; broker.signal = entrySignal(SIGNAL_TIME.plusSeconds(60), 8);
            runner.tick(broker, "paper", SIGNAL_TIME.plusSeconds(60)); assertEquals(0, broker.submits);
        }
    }
    @Test void twoLosingTradesStopFlatAndNeverReenter() throws Exception {
        try (var runner = new PaperTestRunner(root)) {
            var broker = broker(); runner.arm(SIGNAL_PLAN, broker, "paper", START.minusSeconds(3600));
            Instant now = SIGNAL_TIME;
            for (int i = 0; i < 2; i++) {
                broker.signal = entrySignal(now, 2); broker.bid = 700; broker.ask = 700.01; broker.execution = 700;
                runner.tick(broker, "paper", now); broker.fill(); runner.tick(broker, "paper", now.plusSeconds(15));
                broker.bid = 698; broker.execution = 698;
                runner.tick(broker, "paper", now.plusSeconds(30)); broker.fill(); runner.tick(broker, "paper", now.plusSeconds(45));
                now = now.plusSeconds(360);
            }
            assertEquals(2, runner.state().consecutiveLosses); assertEquals("COMPLETE", runner.state().phase);
            runner.tick(broker, "paper", now); assertEquals(4, broker.submits); assertEquals(0, broker.position.signum());
        }
    }
}
