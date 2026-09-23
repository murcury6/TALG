package io.github.murcury6.talg;

import java.time.Duration;
import java.time.Instant;

/** Deliberately simple, long-only research gate; not a portfolio optimizer. */
public final class RiskEngine {
    private static final Duration MAX_SIGNAL_AGE = Duration.ofHours(72);
    private static final double MIN_EXPECTED_ONE_DAY_RETURN = 0.001;
    private static final double MAX_POSITION_FRACTION = 0.05;
    private static final double ANNUAL_VOLATILITY_BUDGET = 0.01;

    public Decision evaluate(Signal signal, Instant evaluatedAt, double capitalUsd) {
        if (signal == null || evaluatedAt == null) {
            throw new IllegalArgumentException("Signal and evaluation time are required");
        }
        if (!Double.isFinite(capitalUsd) || capitalUsd <= 0) {
            throw new IllegalArgumentException("Capital must be positive and finite");
        }
        if (signal.asOfUtc().isAfter(evaluatedAt)) {
            return Decision.reject("FUTURE_SIGNAL");
        }
        if (Duration.between(signal.asOfUtc(), evaluatedAt).compareTo(MAX_SIGNAL_AGE) > 0) {
            return Decision.reject("STALE_SIGNAL");
        }
        if (signal.expectedReturn() <= MIN_EXPECTED_ONE_DAY_RETURN) {
            return Decision.reject("INSUFFICIENT_EDGE");
        }

        double fraction = Math.min(MAX_POSITION_FRACTION,
                ANNUAL_VOLATILITY_BUDGET / signal.forecastAnnualVolatility());
        double notional = capitalUsd * fraction;
        long shares = (long) Math.floor(notional / signal.priceUsd());
        if (shares < 1) {
            return Decision.reject("BELOW_ONE_SHARE");
        }
        double roundedNotional = shares * signal.priceUsd();
        return new Decision("RESEARCH_CANDIDATE", shares, roundedNotional, "PASS");
    }
}
