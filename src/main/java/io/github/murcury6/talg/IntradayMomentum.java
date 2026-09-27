package io.github.murcury6.talg;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/** Fixed, long-only research hypothesis. The same closed-bar signal is used live and in replay. */
final class IntradayMomentum {
    record Bar(String time, double open, double high, double low, double close, double volume, double vwap) {}
    record Signal(boolean enter, boolean trendExit, double close, double atr, double fast, double slow,
                  double vwap, double risk, String asOf, String reason) {}
    static Signal evaluate(List<Bar> bars) {
        if (bars.size() < 22) return new Signal(false, false, 0, 0, 0, 0, 0, 0, "", "Waiting for 22 completed regular-session minute bars");
        double fast = bars.getFirst().close(), slow = fast, previousSlow = slow, weighted = 0, volume = 0;
        double trueRanges = 0, priorVolume = 0, priorHigh = 0;
        for (int i = 0; i < bars.size(); i++) {
            Bar bar = bars.get(i);
            if (!Double.isFinite(bar.close()) || bar.close() <= 0 || !Double.isFinite(bar.volume()) || bar.volume() <= 0)
                throw new IllegalArgumentException("Invalid minute observation");
            previousSlow = slow; fast += (bar.close() - fast) * 2.0 / 10; slow += (bar.close() - slow) * 2.0 / 22;
            weighted += (Double.isFinite(bar.vwap()) && bar.vwap() > 0 ? bar.vwap() : (bar.high() + bar.low() + bar.close()) / 3) * bar.volume();
            volume += bar.volume();
            if (i >= bars.size() - 14) {
                double previous = i == 0 ? bar.open() : bars.get(i - 1).close();
                trueRanges += Math.max(bar.high() - bar.low(), Math.max(Math.abs(bar.high() - previous), Math.abs(bar.low() - previous)));
            }
            if (i >= bars.size() - 21 && i < bars.size() - 1) priorVolume += bar.volume();
            if (i >= bars.size() - 6 && i < bars.size() - 1) priorHigh = Math.max(priorHigh, bar.high());
        }
        Bar last = bars.getLast(); double atr = trueRanges / 14, vwap = weighted / volume;
        double risk = Math.max(atr * 1.5, last.close() * .0015);
        LocalTime time = Instant.parse(last.time()).atZone(PaperTestRunner.EASTERN).toLocalTime();
        boolean timeAllowed = !time.isBefore(LocalTime.of(9, 50)) && time.isBefore(LocalTime.of(11, 40));
        boolean trend = fast > slow && slow > previousSlow && last.close() > vwap;
        boolean breakout = last.close() > priorHigh && last.close() > last.open();
        boolean liquid = last.volume() >= priorVolume / 20;
        boolean enter = timeAllowed && trend && breakout && liquid && risk <= last.close() * .004;
        String reason = !timeAllowed ? "Outside entry window (09:50–11:40 Eastern)" : !trend ? "No rising trend above session VWAP"
                : !breakout ? "No close above the preceding five-minute high" : !liquid ? "Breakout volume below prior 20-bar average"
                : risk > last.close() * .004 ? "Volatility exceeds entry risk limit" : "Rising EMA trend + VWAP + five-minute breakout + volume";
        return new Signal(enter, last.close() < slow && last.close() < vwap, last.close(), atr, fast, slow, vwap, risk,
                Instant.parse(last.time()).plusSeconds(60).toString(), reason);
    }
    private IntradayMomentum() {}
}
