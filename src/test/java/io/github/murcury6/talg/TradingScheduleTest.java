package io.github.murcury6.talg;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradingScheduleTest {
    @Test void clipsHalfDaysAndHandlesDaylightSaving() {
        var schedule = TradingSchedule.fullDay();
        var normal = schedule.window(new TradingSchedule.Day("2026-09-23", true, "09:30", "16:00"));
        assertEquals(Instant.parse("2026-09-23T08:00:00Z"), normal.start()); assertEquals(Instant.parse("2026-09-24T00:00:00Z"), normal.end());
        var half = schedule.window(new TradingSchedule.Day("2026-11-27", true, "09:30", "13:00"));
        assertEquals(Instant.parse("2026-11-27T09:00:00Z"), half.start()); assertEquals(Instant.parse("2026-11-27T22:00:00Z"), half.end());
        assertEquals(Instant.parse("2026-11-27T21:55:00Z"), half.entryEnd());
        assertNull(schedule.window(new TradingSchedule.Day("2026-12-25", false, "09:30", "16:00")));
        assertFalse(normal.open(normal.end())); assertTrue(normal.open(normal.start()));
    }
    @Test void extendedOrderBodyIsDayLimitAndExplicitlyEligible() throws Exception {
        var body = PaperTestRunner.JSON.readTree(RapidPaperRunner.Alpaca.orderBody("AAPL", "buy", BigDecimal.ONE, new BigDecimal("100.01"), "test", true));
        assertEquals("limit", body.path("type").asText()); assertEquals("day", body.path("time_in_force").asText()); assertTrue(body.path("extended_hours").asBoolean());
        var regular = PaperTestRunner.JSON.readTree(RapidPaperRunner.Alpaca.orderBody("AAPL", "sell", BigDecimal.ONE, new BigDecimal("100.01"), "test2", false));
        assertFalse(regular.path("extended_hours").asBoolean());
    }
    @Test void hoursAndLimitsFormRoundTripsWithoutJsonEditing() throws Exception {
        var model = RapidPaperModel.defaults(); var form = new RapidScheduleForm(model);
        form.start.setText("06:00"); form.end.setText("19:00"); form.repeat.setSelected(false); form.scan.setValue(20); form.entries.setValue(500); form.perStock.setValue(50);
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) PaperTestRunner.JSON.valueToTree(model); form.apply(tree);
        var updated = PaperTestRunner.JSON.treeToValue(tree, RapidPaperModel.class);
        assertEquals("06:00", updated.schedule().start()); assertEquals("19:00", updated.schedule().end()); assertFalse(updated.schedule().repeatTradingDays());
        assertEquals(20, updated.schedule().scanSeconds()); assertEquals(500, updated.portfolio().maxEntries()); assertEquals(50, updated.portfolio().maxEntriesPerStock());
    }
    @Test void speedAndSizeFormPreservesOtherBricksAndSynchronizesScriptQuantity() throws Exception {
        var model = RapidPaperModel.defaults(); var form = new RapidVolumeForm(model);
        form.shares.setValue(100); form.entry.setValue(5000); form.exposure.setValue(50000); form.cooldown.setValue(10); form.hold.setValue(60); form.posts.setValue(24);
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) PaperTestRunner.JSON.valueToTree(model); form.apply(tree);
        var updated = PaperTestRunner.JSON.treeToValue(tree, RapidPaperModel.class);
        assertEquals(100, updated.sizing().shares()); assertEquals(5000, updated.sizing().maxEntryDollars()); assertEquals(50000, updated.sizing().maxExposureDollars());
        assertEquals(60, updated.exits().holdSeconds()); assertEquals(10, updated.execution().cooldownSeconds()); assertTrue(updated.signal().script().contains("qty 100"));
        assertEquals(model.prediction(), updated.prediction()); assertEquals(model.exits().minimumRiskFraction(), updated.exits().minimumRiskFraction());
    }
    @Test void multiShareOrderPayloadKeepsPaperEntryBoundButAllowsFullExit() throws Exception {
        var body = PaperTestRunner.JSON.readTree(RapidPaperRunner.Alpaca.orderBody("AAPL", "buy", new BigDecimal("40"), new BigDecimal("100"), "test", true));
        assertEquals("40", body.path("qty").asText()); assertTrue(body.path("extended_hours").asBoolean());
        assertThrows(IllegalArgumentException.class, () -> RapidPaperRunner.Alpaca.orderBody("AAPL", "buy", new BigDecimal("60"), new BigDecimal("100"), "test", true));
        assertDoesNotThrow(() -> RapidPaperRunner.Alpaca.orderBody("AAPL", "sell", new BigDecimal("50"), new BigDecimal("110"), "test", true));
    }
}
