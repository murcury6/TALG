package io.github.murcury6.talg;

import java.time.*;
import java.util.*;

/** Standardized ridge regression. Every feature uses only bars known at prediction time. */
record StatisticalReturnModel(int version, String trainedThrough, List<String> symbols, List<String> features,
                              double[] means, double[] scales, double[] weights, Map<String, Double> validation) {
    static final List<String> FEATURES = List.of("return_1m", "return_3m", "return_5m", "volatility_5m", "range_fraction", "volume_ratio", "vwap_deviation");
    StatisticalReturnModel {
        if (version != 1 || !FEATURES.equals(features) || means.length != FEATURES.size() || scales.length != FEATURES.size() || weights.length != FEATURES.size() + 1)
            throw new IllegalArgumentException("Incompatible statistical model");
        for (double x : means) if (!Double.isFinite(x)) throw new IllegalArgumentException("Invalid feature means");
        for (double x : scales) if (!Double.isFinite(x) || x <= 0) throw new IllegalArgumentException("Invalid feature scale");
        for (double x : weights) if (!Double.isFinite(x)) throw new IllegalArgumentException("Invalid model weights");
    }
    record Example(String date, String symbol, double[] x, double y, double price) {}
    static double[] features(List<IntradayMomentum.Bar> bars) {
        if (bars.size() < 8) throw new IllegalArgumentException("Statistics need eight completed minute observations");
        int n = bars.size(); var b = bars.getLast(); double mean = 0, square = 0, volume = 0;
        for (int i = n - 5; i < n; i++) { double r = Math.log(bars.get(i).close() / bars.get(i - 1).close()); mean += r; square += r * r; volume += bars.get(i - 1).volume(); }
        mean /= 5; double volatility = Math.sqrt(Math.max(0, square / 5 - mean * mean));
        double vwap = Double.isFinite(b.vwap()) && b.vwap() > 0 ? b.vwap() : (b.high() + b.low() + b.close()) / 3;
        return new double[]{Math.log(b.close() / bars.get(n - 2).close()), Math.log(b.close() / bars.get(n - 4).close()), Math.log(b.close() / bars.get(n - 6).close()),
                volatility, (b.high() - b.low()) / b.close(), b.volume() / Math.max(1, volume / 5) - 1, b.close() / vwap - 1};
    }
    double predict(List<IntradayMomentum.Bar> bars) { return predict(features(bars)); }
    double predict(double[] x) {
        double prediction = weights[0];
        for (int j = 0; j < x.length; j++) prediction += weights[j + 1] * Math.max(-8, Math.min(8, (x[j] - means[j]) / scales[j]));
        if (!Double.isFinite(prediction)) throw new IllegalArgumentException("Non-finite model output");
        return Math.expm1(prediction);
    }
    static List<Example> examples(String symbol, List<IntradayMomentum.Bar> bars) {
        List<Example> result = new ArrayList<>();
        for (int i = 7; i + 2 < bars.size(); i++) {
            Instant first = Instant.parse(bars.get(i - 7).time()), last = Instant.parse(bars.get(i + 2).time());
            if (!first.atZone(PaperTestRunner.EASTERN).toLocalDate().equals(last.atZone(PaperTestRunner.EASTERN).toLocalDate()) || Duration.between(first, last).getSeconds() != 9 * 60) continue;
            var b = bars.get(i); result.add(new Example(b.time().substring(0, 10), symbol, features(bars.subList(i - 7, i + 1)), Math.log(bars.get(i + 2).close() / b.close()), b.close()));
        }
        return result;
    }
    static StatisticalReturnModel fit(List<Example> examples, List<String> symbols, String trainedThrough) {
        if (examples.size() < 500) throw new IllegalArgumentException("At least 500 training examples required");
        int p = FEATURES.size(), n = examples.size(); double[] means = new double[p], scales = new double[p];
        for (var e : examples) for (int j = 0; j < p; j++) means[j] += e.x[j] / n;
        for (var e : examples) for (int j = 0; j < p; j++) scales[j] += Math.pow(e.x[j] - means[j], 2) / n;
        for (int j = 0; j < p; j++) scales[j] = Math.max(1e-9, Math.sqrt(scales[j]));
        double[][] matrix = new double[p + 1][p + 2];
        for (var e : examples) {
            double[] x = new double[p + 1]; x[0] = 1;
            for (int j = 0; j < p; j++) x[j + 1] = Math.max(-8, Math.min(8, (e.x[j] - means[j]) / scales[j]));
            for (int j = 0; j <= p; j++) { for (int k = 0; k <= p; k++) matrix[j][k] += x[j] * x[k]; matrix[j][p + 1] += x[j] * e.y; }
        }
        // Fixed regularization chosen before validation; intercept is not penalized.
        for (int j = 1; j <= p; j++) matrix[j][j] += n * .1;
        for (int column = 0; column <= p; column++) {
            int pivot = column; for (int row = column + 1; row <= p; row++) if (Math.abs(matrix[row][column]) > Math.abs(matrix[pivot][column])) pivot = row;
            double[] swap = matrix[column]; matrix[column] = matrix[pivot]; matrix[pivot] = swap;
            double divisor = matrix[column][column]; if (Math.abs(divisor) < 1e-12) throw new IllegalArgumentException("Singular regression");
            for (int k = column; k <= p + 1; k++) matrix[column][k] /= divisor;
            for (int row = 0; row <= p; row++) if (row != column) { double factor = matrix[row][column]; for (int k = column; k <= p + 1; k++) matrix[row][k] -= factor * matrix[column][k]; }
        }
        double[] weights = new double[p + 1]; for (int j = 0; j <= p; j++) weights[j] = matrix[j][p + 1];
        return new StatisticalReturnModel(1, trainedThrough, List.copyOf(symbols), FEATURES, means, scales, weights, Map.of("trainingExamples", (double) n));
    }
    StatisticalReturnModel validate(List<Example> later) {
        if (later.isEmpty()) throw new IllegalArgumentException("Missing chronological validation set");
        double absolute = 0, baseline = 0, squared = 0, baseSquared = 0, correct = 0, selected = 0, net = 0;
        for (var e : later) {
            double prediction = predict(e.x), actual = Math.expm1(e.y); absolute += Math.abs(prediction - actual); baseline += Math.abs(actual);
            squared += Math.pow(prediction - actual, 2); baseSquared += actual * actual; if (Math.signum(prediction) == Math.signum(actual)) correct++;
            double cost = .0004 + .02 / e.price;
            if (prediction > cost) { selected++; net += actual - cost; }
        }
        Map<String, Double> report = new LinkedHashMap<>(validation); report.put("validationExamples", (double) later.size());
        report.put("maeBasisPoints", absolute / later.size() * 10000); report.put("zeroForecastMaeBasisPoints", baseline / later.size() * 10000);
        report.put("directionAccuracy", correct / later.size()); report.put("mseImprovementVsZero", 1 - squared / baseSquared);
        report.put("predictionsAboveAssumedCosts", selected); report.put("meanSelectedNetBasisPoints", selected == 0 ? 0 : net / selected * 10000);
        return new StatisticalReturnModel(version, trainedThrough, symbols, features, means, scales, weights, Map.copyOf(report));
    }
}
