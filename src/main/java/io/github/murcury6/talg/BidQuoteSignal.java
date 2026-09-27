package io.github.murcury6.talg;

import java.time.*;

/** Observed top-of-book changes, not a fitted order-book or execution-probability model. */
final class BidQuoteSignal {
    record Move(boolean newer, boolean changed, boolean comparable, boolean hasSizes, double bidChangeBps, double imbalance) {}
    static Move between(IntradayMarketData.Quote previous, IntradayMarketData.Quote current, Instant now) {
        if (!valid(current, now)) return new Move(false, false, false, false, 0, 0);
        boolean sizes = Double.isFinite(current.bidSize() + current.askSize()) && current.bidSize() > 0 && current.askSize() > 0;
        double imbalance = sizes ? (current.bidSize() - current.askSize()) / (current.bidSize() + current.askSize()) : 0;
        if (previous == null) return new Move(true, false, false, sizes, 0, imbalance);
        Instant at = Instant.parse(current.time()), before;
        try { before = Instant.parse(previous.time()); } catch (RuntimeException error) { return new Move(true, false, false, sizes, 0, imbalance); }
        if (!at.isAfter(before)) return new Move(false, false, false, sizes, 0, imbalance);
        boolean comparable = valid(previous, now);
        boolean changed = comparable && (current.bid() != previous.bid() || current.ask() != previous.ask()
                || Double.compare(current.bidSize(), previous.bidSize()) != 0 || Double.compare(current.askSize(), previous.askSize()) != 0);
        return new Move(true, changed, comparable, sizes, comparable ? (current.bid() / previous.bid() - 1) * 10000 : 0, imbalance);
    }
    private static boolean valid(IntradayMarketData.Quote quote, Instant now) {
        return quote != null && RapidPaperRunner.fresh(new RapidPaperRunner.Market(quote, null), now);
    }
}
