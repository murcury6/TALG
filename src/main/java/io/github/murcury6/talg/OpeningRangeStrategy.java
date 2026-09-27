package io.github.murcury6.talg;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/** Fixed 15-minute opening-range breakout, long only; no parameters selected by optimization. */
final class OpeningRangeStrategy {
    static IntradayMomentum.Signal evaluate(List<IntradayMomentum.Bar> bars) {
        if (bars.size() < 16) return waitSignal("Waiting for the completed 09:30–09:45 opening range");
        List<IntradayMomentum.Bar> opening = bars.stream().filter(b -> Instant.parse(b.time()).atZone(PaperTestRunner.EASTERN)
                .toLocalTime().isBefore(LocalTime.of(9, 45))).toList();
        if (opening.size() != 15) return waitSignal("Opening range has missing minute bars; skip this session");
        double high = opening.stream().mapToDouble(IntradayMomentum.Bar::high).max().orElseThrow();
        double low = opening.stream().mapToDouble(IntradayMomentum.Bar::low).min().orElseThrow();
        double volume = 0, weighted = 0;
        for (var bar : bars) {
            volume += bar.volume();
            weighted += (Double.isFinite(bar.vwap()) && bar.vwap() > 0 ? bar.vwap() : (bar.high() + bar.low() + bar.close()) / 3) * bar.volume();
        }
        var last = bars.getLast(); var previous = bars.get(bars.size() - 2);
        double vwap = weighted / volume;
        double risk = Math.max(last.close() - low, last.close() * .002);
        double threshold = high * 1.0002;
        LocalTime local = Instant.parse(last.time()).atZone(PaperTestRunner.EASTERN).toLocalTime();
        boolean time = !local.isBefore(LocalTime.of(9, 45)) && local.isBefore(LocalTime.of(11, 0));
        boolean breakout = last.close() > threshold && previous.close() <= threshold && last.close() > last.open();
        boolean enter = time && breakout && last.close() > vwap && risk <= last.close() * .008;
        String reason = !time ? "Outside entry window (09:45–11:00 Eastern)" : !breakout ? "Waiting for a fresh close above the 15-minute opening range"
                : last.close() <= vwap ? "Breakout is below session VWAP" : risk > last.close() * .008 ? "Opening-range risk exceeds 0.8%" : "Opening-range breakout above VWAP";
        return new IntradayMomentum.Signal(enter, last.close() < vwap && previous.close() < vwap,
                last.close(), (high - low) / 3, high, low, vwap, risk, Instant.parse(last.time()).plusSeconds(60).toString(), reason);
    }
    private static IntradayMomentum.Signal waitSignal(String reason) { return new IntradayMomentum.Signal(false, false, 0, 0, 0, 0, 0, 0, "", reason); }
    private OpeningRangeStrategy() {}
}
