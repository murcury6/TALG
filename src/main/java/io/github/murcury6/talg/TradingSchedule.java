package io.github.murcury6.talg;

import java.time.*;

/** Eastern-time schedule, clipped to the broker calendar and supported extended session. */
record TradingSchedule(String start, String end, boolean extendedHours, boolean repeatTradingDays,
                       int scanSeconds, int flattenMinutesBeforeEnd) {
    TradingSchedule {
        LocalTime from = LocalTime.parse(start), to = LocalTime.parse(end);
        if (from.isBefore(LocalTime.of(4, 0)) || to.isAfter(LocalTime.of(20, 0)) || !from.isBefore(to))
            throw new IllegalArgumentException("Hours must be between 04:00 and 20:00 Eastern, start before end");
        if (scanSeconds < 5 || scanSeconds > 60 || flattenMinutesBeforeEnd < 1 || flattenMinutesBeforeEnd > 30
                || Duration.between(from, to).toMinutes() <= flattenMinutesBeforeEnd) throw new IllegalArgumentException("Scan 5–60 seconds; close positions 1–30 minutes before end");
    }
    record Day(String date, boolean tradingDay, String open, String close) {}
    record Window(Instant start, Instant entryEnd, Instant end) {
        boolean open(Instant now) { return !now.isBefore(start) && now.isBefore(end); }
    }
    Window window(Day day) {
        if (!day.tradingDay()) return null;
        LocalDate date = LocalDate.parse(day.date()); LocalTime coreOpen = LocalTime.parse(day.open()), coreClose = LocalTime.parse(day.close());
        LocalTime allowedStart = extendedHours ? LocalTime.of(4, 0) : coreOpen;
        LocalTime allowedEnd = extendedHours ? coreClose.plusHours(4) : coreClose;
        if (allowedEnd.isAfter(LocalTime.of(20, 0))) allowedEnd = LocalTime.of(20, 0);
        LocalTime from = LocalTime.parse(start).isAfter(allowedStart) ? LocalTime.parse(start) : allowedStart;
        LocalTime to = LocalTime.parse(end).isBefore(allowedEnd) ? LocalTime.parse(end) : allowedEnd;
        if (!from.isBefore(to)) return null;
        Instant finish = date.atTime(to).atZone(PaperTestRunner.EASTERN).toInstant();
        return new Window(date.atTime(from).atZone(PaperTestRunner.EASTERN).toInstant(), finish.minusSeconds(flattenMinutesBeforeEnd * 60L), finish);
    }
    static TradingSchedule fullDay() { return new TradingSchedule("04:00", "20:00", true, true, 10, 5); }
    static TradingSchedule legacy() { return new TradingSchedule("09:35", "15:55", false, false, 10, 5); }
}
