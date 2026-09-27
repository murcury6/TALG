package io.github.murcury6.talg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Read-only market-data research. No orders; fixed parameters chosen before inspecting this replay. */
@EnabledIfSystemProperty(named = "talg.momentumResearch", matches = "true")
class MomentumResearchTest {
    record Trade(String date, String entryTime, String exitTime, double entry, double exit, double gross, double net, String reason) {}
    @Test void fetchAndReplayCompletedSessionsWithCostsAndChronologicalHoldout() throws Exception {
        boolean openingRange = Boolean.getBoolean("talg.openingRangeResearch");
        Path folder = Path.of("work/paper-test/research"); Files.createDirectories(folder);
        Path cache = folder.resolve("SPY-2026-07-27-to-2026-09-22-iex.json");
        List<IntradayMomentum.Bar> bars;
        if (Files.exists(cache)) bars = PaperTestRunner.JSON.readValue(cache.toFile(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        else {
            var saved = new CredentialStore().load().orElseThrow(); assertEquals("paper", saved.mode());
            assertEquals("iex", saved.settings().feed(), "Keep this check on the selected free feed");
            bars = new IntradayMarketData(saved.settings()).bars(Instant.parse("2026-07-27T13:30:00Z"), Instant.parse("2026-09-22T20:00:00Z"));
            PaperTestRunner.JSON.writeValue(cache.toFile(), bars);
        }
        Map<String, List<IntradayMomentum.Bar>> days = new LinkedHashMap<>();
        for (var bar : bars) days.computeIfAbsent(bar.time().substring(0, 10), ignored -> new ArrayList<>()).add(bar);
        List<Trade> trades = new ArrayList<>();
        int skippedDays = 0;
        for (var entry : days.entrySet()) {
            var day = entry.getValue();
            if (day.size() < 150) { skippedDays++; continue; }
            double bought = 0, stop = 0, target = 0, risk = 0, dailyNet = 0;
            Instant entryAt = null, cooldown = Instant.MIN; int completed = 0, losses = 0;
            for (int i = openingRange ? 16 : 22; i < day.size(); i++) {
                var bar = day.get(i); Instant now = Instant.parse(bar.time());
                LocalTime local = now.atZone(PaperTestRunner.EASTERN).toLocalTime();
                var previous = openingRange ? OpeningRangeStrategy.evaluate(day.subList(0, i)) : IntradayMomentum.evaluate(day.subList(0, i));
                if (bought == 0 && completed < (openingRange ? 2 : 8) && losses < (openingRange ? 2 : 3) && dailyNet > (openingRange ? -8 : -6) && !now.isBefore(cooldown)
                        && previous.enter() && bar.open() <= previous.close() + previous.atr() * .5
                        && Duration.between(Instant.parse(day.get(Math.max(0, i - 22)).time()), now).getSeconds() <= 26 * 60
                        && Duration.between(Instant.parse(day.get(i - 1).time()), now).getSeconds() <= 90) {
                    bought = bar.open(); risk = previous.risk(); stop = bought - risk; target = bought + (openingRange ? 2 : 1.5) * risk; entryAt = now;
                }
                if (bought != 0) {
                    double exit = 0; String reason = "";
                    if (!local.isBefore(LocalTime.NOON)) { exit = bar.open(); reason = "session end"; }
                    else if (Duration.between(entryAt, now).toMinutes() >= (openingRange ? 60 : 20)) { exit = bar.open(); reason = "time exit"; }
                    else if (Duration.between(entryAt, now).toMinutes() >= (openingRange ? 10 : 1) && previous.trendExit()) { exit = bar.open(); reason = "trend failure"; }
                    else if (bar.low() <= stop) { exit = Math.min(bar.open(), stop); reason = "stop (first if both levels touch)"; }
                    else if (bar.high() >= target) { exit = Math.max(bar.open(), target); reason = "target"; }
                    if (exit > 0) {
                        double gross = exit - bought, net = exit * .9998 - bought * 1.0002 - .02;
                        trades.add(new Trade(entry.getKey(), entryAt.toString(), now.toString(), bought, exit, gross, net, reason));
                        dailyNet += net; completed++; losses = net < 0 ? losses + 1 : 0; bought = 0; cooldown = now.plusSeconds(300);
                    } else if (bar.close() >= bought + risk) stop = Math.max(stop, bar.close() - (openingRange ? 1 : .75) * risk);
                }
                if (!local.isBefore(LocalTime.NOON)) break;
            }
            assertEquals(0, bought, "Replay must not drop an open position at the end of available data");
        }
        assertTrue(days.size() >= 20, "Need enough distinct sessions to report anything");
        var dates = new ArrayList<>(days.keySet()); String holdoutStart = dates.get((int) (dates.size() * .75));
        var development = trades.stream().filter(t -> t.date().compareTo(holdoutStart) < 0).toList();
        var holdout = trades.stream().filter(t -> t.date().compareTo(holdoutStart) >= 0).toList();
        Map<String, Object> report = Map.of("strategy", openingRange ? "SPY opening-range breakout v1; separate exploratory candidate, not an untouched validation set" : "SPY trend/vwap breakout v1; parameters fixed before replay",
                "feed", "IEX only", "sessions", days.size(), "skippedSparseDays", skippedDays,
                "holdoutStarts", holdoutStart, "costAssumption", "2 basis points per side plus $0.01 per order; one share",
                "development", summary(development), "holdout", summary(holdout), "all", summary(trades),
                "limitations", "Small historical sample; IEX is one exchange. Bar replay is not the live execution simulator. No guarantee of future edge.");
        String prefix = openingRange ? "opening-range" : "momentum";
        PaperTestRunner.JSON.writerWithDefaultPrettyPrinter().writeValue(folder.resolve(prefix + "-replay-summary.json").toFile(), report);
        PaperTestRunner.JSON.writerWithDefaultPrettyPrinter().writeValue(folder.resolve(prefix + "-replay-trades.json").toFile(), trades);
        System.out.println(PaperTestRunner.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
    static Map<String, Object> summary(List<Trade> trades) {
        return Map.of("trades", trades.size(), "grossDollars", trades.stream().mapToDouble(Trade::gross).sum(),
                "netDollarsAfterAssumedCosts", trades.stream().mapToDouble(Trade::net).sum(),
                "winnersAfterCosts", trades.stream().filter(t -> t.net() > 0).count());
    }
}
