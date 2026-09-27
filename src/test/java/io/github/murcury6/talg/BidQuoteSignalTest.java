package io.github.murcury6.talg;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BidQuoteSignalTest {
    final Instant now = Instant.parse("2026-09-24T15:00:10Z");
    IntradayMarketData.Quote quote(double bid, double bidSize, double askSize, Instant at) { return new IntradayMarketData.Quote(bid, bid + .01, at.toString(), bidSize, askSize); }
    @Test void improvingBidNeedsPositiveDisplayedSizeImbalance() {
        var before = quote(100, 100, 100, now.minusSeconds(5)); var rules = RapidPaperModel.BidRules.active();
        var up = BidQuoteSignal.between(before, quote(100.01, 300, 100, now), now);
        assertEquals(1, up.bidChangeBps(), .00001); assertEquals(.5, up.imbalance()); assertTrue(rules.enter(up)); assertFalse(rules.exit(up));
        assertFalse(rules.enter(BidQuoteSignal.between(before, quote(100.01, 100, 300, now), now)));
        assertFalse(rules.enter(BidQuoteSignal.between(before, quote(100, 300, 100, now), now)));
    }
    @Test void duplicateOlderStaleMissingAndUnchangedQuotesCannotCreateEntries() {
        var before = quote(100, 300, 100, now.minusSeconds(5)); var rules = RapidPaperModel.BidRules.active();
        assertFalse(rules.enter(BidQuoteSignal.between(null, quote(100.01, 300, 100, now), now)));
        assertFalse(BidQuoteSignal.between(before, before, now).changed());
        assertFalse(BidQuoteSignal.between(before, quote(100.01, 300, 100, now.minusSeconds(6)), now).newer());
        assertFalse(BidQuoteSignal.between(before, quote(100, 300, 100, now), now).changed());
        assertFalse(rules.enter(BidQuoteSignal.between(before, new IntradayMarketData.Quote(100.01, 100.02, now.toString()), now)));
        assertFalse(rules.enter(BidQuoteSignal.between(before, quote(100.01, 300, 100, now), now.plusSeconds(60))));
        assertFalse(BidQuoteSignal.between(quote(100, 300, 100, now.minusSeconds(40)), quote(100.01, 300, 100, now), now).comparable());
    }
    @Test void priceReversalOrAskHeavySizeCanSignalExit() {
        var before = quote(100, 300, 100, now.minusSeconds(5)); var rules = RapidPaperModel.BidRules.active();
        assertTrue(rules.exit(BidQuoteSignal.between(before, quote(99.98, 300, 100, now), now)));
        assertTrue(rules.exit(BidQuoteSignal.between(before, quote(100, 100, 300, now), now)));
    }
    @Test void editableBidFormDisablesTimedExitAndPreservesStatisticalModel() throws Exception {
        var model = RapidPaperModel.fastPaper(); var form = new BidRulesForm(RapidPaperModel.BidRules.active());
        form.rise.setValue(.2); form.imbalance.setValue(.3);
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) PaperTestRunner.JSON.valueToTree(model); form.apply(tree);
        var updated = PaperTestRunner.JSON.treeToValue(tree, RapidPaperModel.class);
        assertEquals(0, updated.exits().holdSeconds()); assertEquals(.2, updated.signal().bidRules().minRiseBps());
        assertEquals(.3, updated.signal().bidRules().minImbalance()); assertEquals(model.prediction(), updated.prediction()); assertEquals(model.signal().script(), updated.signal().script());
    }
    @Test void customLanguageRuleChangesDecisionsWithoutChangingRuntime() throws Exception {
        var falling = BidQuoteSignal.between(quote(100, 100, 100, now.minusSeconds(5)), quote(99.98, 300, 100, now), now);
        var custom = new RapidPaperModel.BidRules(true, .1, .1, 1, -.25,
                "bid_change_bps < -min_bid_rise_bps and bid_imbalance > min_bid_imbalance", "bid_change_bps > 5");
        assertFalse(RapidPaperModel.BidRules.active().enter(falling)); assertTrue(custom.enter(falling)); assertFalse(custom.exit(falling));
        var restored = PaperTestRunner.JSON.readValue(PaperTestRunner.JSON.writeValueAsString(custom), RapidPaperModel.BidRules.class);
        assertEquals(custom, restored); assertTrue(restored.enter(falling));
        assertFalse(custom.enter(BidQuoteSignal.between(null, quote(99.98, 300, 100, now), now)), "Expressions cannot bypass fresh comparable quote guards");
    }
    @Test void formPreservesCustomExpressionsWhenChangingParameters() throws Exception {
        var custom = new RapidPaperModel.BidRules(true, .1, .1, 1, -.25, "bid_change_bps > 2 * min_bid_rise_bps", "has_sizes and bid_imbalance < -0.8");
        var form = new BidRulesForm(custom); form.rise.setValue(.4);
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) PaperTestRunner.JSON.valueToTree(RapidPaperModel.fastPaper()); form.apply(tree);
        var model = PaperTestRunner.JSON.treeToValue(tree, RapidPaperModel.class);
        assertEquals(custom.entryExpression(), model.signal().bidRules().entryExpression()); assertEquals(custom.exitExpression(), model.signal().bidRules().exitExpression());
        assertEquals(.4, model.signal().bidRules().minRiseBps());
    }
    @Test void badExpressionsAreRejectedBeforeArmingAndNonFiniteResultsCannotEnter() {
        assertThrows(IllegalArgumentException.class, () -> new RapidPaperModel.BidRules(true, .1, .1, 1, -.25, "made_up_field > 0", "1"));
        assertThrows(IllegalArgumentException.class, () -> new RapidPaperModel.BidRules(true, .1, .1, 1, -.25, "", "1"));
        var invalid = new RapidPaperModel.BidRules(true, .1, .1, 1, -.25, "1 / 0", "0");
        assertFalse(invalid.enter(BidQuoteSignal.between(quote(100, 100, 100, now.minusSeconds(5)), quote(100.01, 300, 100, now), now)));
    }
}
