package io.github.murcury6.talg;

import java.util.*;

/** Declarative, swappable model bricks; no credentials or order submission code belongs here. */
record RapidPaperModel(int version, List<String> universe, Prediction prediction, Signal signal, Sizing sizing, Exits exits,
                       Portfolio portfolio, Execution execution, TradingSchedule schedule) {
    RapidPaperModel(int version, List<String> universe, Prediction prediction, Signal signal, Sizing sizing, Exits exits, Portfolio portfolio, Execution execution) {
        this(version, universe, prediction, signal, sizing, exits, portfolio, execution, TradingSchedule.legacy());
    }
    record Signal(String script, int warmupBars, BidRules bidRules) {
        Signal(String script, int warmupBars) { this(script, warmupBars, BidRules.disabled()); }
        Signal { if (bidRules == null) bidRules = BidRules.disabled(); }
    }
    record BidRules(boolean enabled, double minRiseBps, double minImbalance, double exitFallBps, double exitImbalance,
                    String entryExpression, String exitExpression) {
        static final String ENTRY = "bid_change_bps >= min_bid_rise_bps and bid_imbalance >= min_bid_imbalance";
        static final String EXIT = "bid_change_bps <= -exit_bid_fall_bps or (has_sizes and bid_imbalance <= exit_bid_imbalance)";
        static final Set<String> FIELDS = Set.of("bid_change_bps", "bid_imbalance", "has_sizes", "min_bid_rise_bps", "min_bid_imbalance", "exit_bid_fall_bps", "exit_bid_imbalance");
        BidRules(boolean enabled, double minRiseBps, double minImbalance, double exitFallBps, double exitImbalance) {
            this(enabled, minRiseBps, minImbalance, exitFallBps, exitImbalance, ENTRY, EXIT);
        }
        BidRules {
            if (entryExpression == null) entryExpression = ENTRY;
            if (exitExpression == null) exitExpression = EXIT;
            if (!bounded(minRiseBps, .01, 20) || !bounded(minImbalance, 0, .95)
                    || !bounded(exitFallBps, .01, 50) || !bounded(exitImbalance, -.95, 0)) throw new IllegalArgumentException("Invalid bid movement / size imbalance thresholds");
            StrategyScript.observedExpression(entryExpression, FIELDS);
            StrategyScript.observedExpression(exitExpression, FIELDS);
        }
        static BidRules disabled() { return new BidRules(false, .1, .1, 1, -.25); }
        static BidRules active() { return new BidRules(true, .1, .1, 1, -.25); }
        boolean enter(BidQuoteSignal.Move move) { return move.changed() && move.comparable() && move.hasSizes() && evaluate(entryExpression, move); }
        boolean exit(BidQuoteSignal.Move move) { return move.changed() && move.comparable() && evaluate(exitExpression, move); }
        private boolean evaluate(String expression, BidQuoteSignal.Move move) {
            try {
                double result = StrategyScript.observedExpression(expression, FIELDS).eval(Map.of("bid_change_bps", move.bidChangeBps(), "bid_imbalance", move.imbalance(),
                        "has_sizes", move.hasSizes() ? 1.0 : 0.0, "min_bid_rise_bps", minRiseBps, "min_bid_imbalance", minImbalance,
                        "exit_bid_fall_bps", exitFallBps, "exit_bid_imbalance", exitImbalance));
                return Double.isFinite(result) && result != 0;
            } catch (IllegalArgumentException error) {
                // Invalid arithmetic cannot authorize a trade or interrupt independent risk exits.
                return false;
            }
        }
    }
    record Prediction(String modelFile) {}
    /** Shares is a ceiling; the dollar budget determines whole-share quantity. */
    record Sizing(int shares, double maxEntryDollars, Double maxExposureDollars) {
        Sizing(int shares, double maxEntryDollars) { this(shares, maxEntryDollars, 5000.0); }
        Sizing { if (maxExposureDollars == null) maxExposureDollars = 5000.0; }
    }
    record Exits(double minimumRiskFraction, double maximumRiskFraction, double targetR, double trailR, int holdSeconds) {}
    record Portfolio(int maxPositions, int maxEntries, int maxEntriesPerStock, double lossDollars) {}
    record Execution(int cooldownSeconds, int postsPerMinute, double maxSpreadFraction, double maxChaseR) {}
    RapidPaperModel {
        if (schedule == null) schedule = TradingSchedule.legacy();
        if (version != 1 || universe == null || universe.isEmpty() || universe.size() > 120 || new HashSet<>(universe).size() != universe.size()
                || universe.stream().anyMatch(s -> s == null || !s.matches("[A-Z]{1,5}"))) throw new IllegalArgumentException("Universe: 1–120 unique stock/ETF symbols");
        universe = List.copyOf(universe);
        if (prediction == null || prediction.modelFile == null || !prediction.modelFile.matches("work/models/[a-zA-Z0-9_-]+\\.json") || signal == null || sizing == null || exits == null || portfolio == null || execution == null) throw new IllegalArgumentException("All model bricks are required; predictor must be a JSON file under work/models");
        if (signal.warmupBars != 8) throw new IllegalArgumentException("Regression features require eight closed bars");
        var rules = StrategyScript.parse(signal.script, Set.of("previous_close", "predicted_return", "trading_cost", "prediction_rank"));
        if (rules.inputs().stream().anyMatch(input -> !input.kind().equals("observed") && !input.kind().equals("news"))) throw new IllegalArgumentException("Rapid v1 accepts Observed and News inputs; external R/model inputs must first have a streaming adapter");
        if (!rules.bars().equals("1Min") || rules.quantity() != sizing.shares) throw new IllegalArgumentException("Signal must use bars 1Min and match sizing shares");
        if (sizing.shares < 1 || sizing.shares > 100 || !bounded(sizing.maxEntryDollars, 5, 5000)
                || !bounded(sizing.maxExposureDollars, sizing.maxEntryDollars, 50000)) throw new IllegalArgumentException("Paper sizing: 1–100 share ceiling, $5–$5,000 per entry, up to $50,000 total exposure");
        if (!bounded(exits.minimumRiskFraction, .001, .01) || !bounded(exits.maximumRiskFraction, exits.minimumRiskFraction, .02)
                || !bounded(exits.targetR, 1, 5) || !bounded(exits.trailR, .5, 3) || exits.holdSeconds != 0 && exits.holdSeconds < 30 || exits.holdSeconds > 600)
            throw new IllegalArgumentException("Invalid exit risk/target/trail/hold parameters");
        if (signal.bidRules.enabled && exits.holdSeconds != 0) throw new IllegalArgumentException("Bid-driven mode requires timed holding exits disabled (0)");
        if (portfolio.maxPositions < 1 || portfolio.maxPositions > 10 || portfolio.maxEntries < 0 || portfolio.maxEntries > 10000
                || portfolio.maxEntriesPerStock < 0 || portfolio.maxEntriesPerStock > 10000 || !bounded(portfolio.lossDollars, 1, 500)) throw new IllegalArgumentException("Entry caps: 0 (off)–10,000; up to ten positions and $500 daily loss trigger");
        if (execution.cooldownSeconds < 5 || execution.cooldownSeconds > 600 || execution.postsPerMinute < 6 || execution.postsPerMinute > 24
                || execution.postsPerMinute <= portfolio.maxPositions
                || !bounded(execution.maxSpreadFraction, .0001, .002) || !bounded(execution.maxChaseR, 0, 1)) throw new IllegalArgumentException("Invalid execution limits");
    }
    private static boolean bounded(double value, double low, double high) { return Double.isFinite(value) && value >= low && value <= high; }
    static RapidPaperModel fastPaper() {
        var base = defaults();
        return new RapidPaperModel(1, base.universe, base.prediction,
                new Signal(base.signal.script.replace("prediction_rank >= 0.75", "prediction_rank >= 0.5").replace("prediction_rank < 0.5", "prediction_rank < 0.35").replace("qty 1", "qty 100"), 8),
                new Sizing(100, 5000, 50000.0), new Exits(.0015, .008, 1.5, 1, 60),
                new Portfolio(10, 0, 0, 500), new Execution(10, 24, .0008, .5), new TradingSchedule("04:00", "20:00", true, true, 5, 5));
    }
    StrategyScript.Evaluation evaluate(List<IntradayMomentum.Bar> bars, double prediction, double cost, double rank) {
        return evaluate(bars, prediction, cost, rank, Map.of());
    }
    StrategyScript.Evaluation evaluate(List<IntradayMomentum.Bar> bars, double prediction, double cost, double rank, Map<String, Double> newsInputs) {
        var last = bars.getLast();
        Map<String, Double> inputs = new java.util.LinkedHashMap<>(newsInputs);
        inputs.putAll(Map.of("open", last.open(), "high", last.high(), "low", last.low(), "close", last.close(),
                "volume", last.volume(), "vwap", Double.isFinite(last.vwap()) ? last.vwap() : last.close(), "previous_close", bars.get(bars.size() - 2).close(), "predicted_return", prediction, "trading_cost", cost, "prediction_rank", rank));
        return StrategyScript.parse(signal.script, Set.of("previous_close", "predicted_return", "trading_cost", "prediction_rank")).evaluate(inputs);
    }
    static RapidPaperModel defaults() {
        return new RapidPaperModel(1, RapidPaperRunner.SYMBOLS, new Prediction("work/models/rapid-return-v1.json"),
                new Signal("# Applied independently to each universe stock; ticker is a preview placeholder.\nname Rapid Statistical Return\nticker AAPL\nrange 1D\nbars 1Min\n"
                        + "# PAPER VOLUME EXPERIMENT: ranking is not evidence of a positive net return.\n"
                        + "# For cost-gated entries, replace buy with: buy expected_edge > 0\n"
                        + "let expected_edge = predicted_return - trading_cost\nbuy prediction_rank >= 0.75\nsell prediction_rank < 0.5\nqty 1\n", 8),
                new Sizing(1, 1000), new Exits(.0015, .008, 1.5, 1, 120), new Portfolio(5, 0, 0, 50), new Execution(60, 12, .0008, .5), TradingSchedule.fullDay());
    }
}
