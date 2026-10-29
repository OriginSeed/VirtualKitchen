package com.processVisualisation.virtualKitchen.ai.platform.analytics.query;

import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AiRequestAnalytics;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AnalyticsStatus;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.CostBasis;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.DataCompleteness;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Metric definitions for grouped analytics. Pure, so every definition is unit-tested.
 * <ul>
 *     <li><b>requestCount</b>: logical requests (analytics records), not provider calls.</li>
 *     <li><b>attemptCount</b>: provider calls, retries and validation retries included; summed over the requests
 *     that know it ({@code attemptSamples}).</li>
 *     <li><b>retryRate</b>: requests that needed more than one transport attempt ÷ requests whose attempts are known.</li>
 *     <li><b>failureRate</b>: (FAILED + TIMED_OUT) ÷ requests that reached a provider (SUCCEEDED + FAILED + TIMED_OUT).</li>
 *     <li><b>totalCost</b>: sum over <em>costed</em> requests only; {@code uncostedCount} says how many could not be
 *     costed, and {@code costComplete} is false when any could not. Missing cost is never treated as zero. A request's
 *     cost already includes every billable attempt, failed ones included.</li>
 *     <li><b>costPerSuccess</b>: totalCost (failures included) ÷ successful requests.</li>
 *     <li>Every average, median and percentile reports its sample size.</li>
 *     <li>Costs in different currencies are never added: a mixed group reports no total.</li>
 * </ul>
 */
public final class AnalyticsMath {

    private AnalyticsMath() {
    }

    /** The fields a metric needs from one record. */
    public record Row(Map<String, String> key, AnalyticsStatus status, Integer attemptCount, Integer transportAttempts,
                      Double totalCost, CostBasis costBasis, Double directCost, Double infraCost, String currency,
                      Long inputTokens, Long outputTokens, Long totalMs, Long queueWaitMs, Boolean validationPassed,
                      boolean historical) {

        public static Row of(AiRequestAnalytics record, Map<String, String> key) {
            AiRequestAnalytics.Cost cost = record.getCostTotals();
            AiRequestAnalytics.Usage usage = record.getUsageTotals();
            boolean costed = cost != null && cost.getCostBasis() != null && cost.getCostBasis() != CostBasis.UNAVAILABLE
                    && cost.getTotalCost() != null;
            return new Row(key, record.getStatus(), record.getAttemptCount(), record.getTransportAttempts(),
                    costed ? cost.getTotalCost() : null,
                    cost == null ? CostBasis.UNAVAILABLE : cost.getCostBasis(),
                    costed ? cost.getDirectCost() : null,
                    costed ? cost.getInfraCost() : null,
                    cost == null ? null : cost.getCurrency(),
                    usage == null ? null : usage.getInputTokens(),
                    usage == null ? null : usage.getOutputTokens(),
                    record.getTotalMs(), record.getQueueWaitMs(), record.getValidationPassed(),
                    record.getDataCompleteness() == DataCompleteness.HISTORICAL_PARTIAL);
        }
    }

    public record Stat(Double value, int samples) {
        static Stat none() {
            return new Stat(null, 0);
        }
    }

    public record GroupMetrics(
            Map<String, String> key,
            int requestCount,
            Long attemptCount, int attemptSamples,
            int succeeded, int failed, int timedOut, int rejected, int reused, int cancelled, int inFlight,
            Double retryRate, int retrySamples,
            Double failureRate,
            Double totalCost, int costedCount, int uncostedCount, boolean costComplete,
            Set<String> currencies, Set<String> costBases,
            Stat avgCost, Stat medianCost, Double minCost, Double maxCost,
            Double costPerSuccess,
            Double directCost, Double infraCost, int infraCostSamples,
            Stat avgInputTokens, Stat avgOutputTokens,
            Stat avgLatencyMs, Stat p50LatencyMs, Stat p95LatencyMs, Stat avgQueueWaitMs,
            Double validationPassRate, int validationSamples,
            int historicalCount) {
    }

    /** Groups rows by their key (insertion order preserved) and summarizes each group. */
    public static List<GroupMetrics> group(Collection<Row> rows) {
        Map<Map<String, String>, List<Row>> groups = new LinkedHashMap<>();
        for (Row row : rows) {
            groups.computeIfAbsent(row.key(), k -> new ArrayList<>()).add(row);
        }
        List<GroupMetrics> result = new ArrayList<>();
        groups.forEach((key, members) -> result.add(summarize(key, members)));
        return result;
    }

    public static GroupMetrics summarize(Map<String, String> key, List<Row> rows) {
        int succeeded = 0, failed = 0, timedOut = 0, rejected = 0, reused = 0, cancelled = 0, inFlight = 0;
        long attempts = 0;
        int attemptSamples = 0, retried = 0, retrySamples = 0, historical = 0, validationPassed = 0, validationSamples = 0;
        int infraSamples = 0;
        List<Double> costs = new ArrayList<>();
        double directCost = 0, infraCost = 0;
        int uncosted = 0;
        Set<String> currencies = new LinkedHashSet<>();
        Set<String> bases = new LinkedHashSet<>();
        List<Double> inputTokens = new ArrayList<>();
        List<Double> outputTokens = new ArrayList<>();
        List<Double> latencies = new ArrayList<>();
        List<Double> queueWaits = new ArrayList<>();

        for (Row row : rows) {
            if (row.status() != null) {
                switch (row.status()) {
                    case SUCCEEDED -> succeeded++;
                    case FAILED -> failed++;
                    case TIMED_OUT -> timedOut++;
                    case REJECTED -> rejected++;
                    case REUSED -> reused++;
                    case CANCELLED -> cancelled++;
                    case QUEUED, RUNNING -> inFlight++;
                }
            }
            if (row.attemptCount() != null) {
                attempts += row.attemptCount();
                attemptSamples++;
            }
            if (row.transportAttempts() != null && row.transportAttempts() > 0) {
                retrySamples++;
                if (row.transportAttempts() > 1) retried++;
            }
            if (row.totalCost() != null) {
                costs.add(row.totalCost());
                if (row.currency() != null) currencies.add(row.currency());
                if (row.directCost() != null) directCost += row.directCost();
                if (row.infraCost() != null) {
                    infraCost += row.infraCost();
                    infraSamples++;
                }
            } else if (row.status() != AnalyticsStatus.REJECTED && row.status() != AnalyticsStatus.REUSED
                    && row.status() != AnalyticsStatus.QUEUED && row.status() != AnalyticsStatus.RUNNING) {
                // Rejected and reused requests made no provider call, and in-flight ones have not finished, so none
                // of them has a cost that is missing.
                uncosted++;
            }
            if (row.costBasis() != null) bases.add(row.costBasis().name());
            if (row.inputTokens() != null) inputTokens.add(row.inputTokens().doubleValue());
            if (row.outputTokens() != null) outputTokens.add(row.outputTokens().doubleValue());
            if (row.totalMs() != null) latencies.add(row.totalMs().doubleValue());
            if (row.queueWaitMs() != null) queueWaits.add(row.queueWaitMs().doubleValue());
            if (row.validationPassed() != null) {
                validationSamples++;
                if (row.validationPassed()) validationPassed++;
            }
            if (row.historical()) historical++;
        }

        boolean mixedCurrency = currencies.size() > 1;
        Double total = costs.isEmpty() || mixedCurrency ? null : sum(costs);
        int reachedProvider = succeeded + failed + timedOut;
        return new GroupMetrics(
                key, rows.size(),
                attemptSamples == 0 ? null : attempts, attemptSamples,
                succeeded, failed, timedOut, rejected, reused, cancelled, inFlight,
                ratio(retried, retrySamples), retrySamples,
                ratio(failed + timedOut, reachedProvider),
                total, costs.size(), uncosted, uncosted == 0 && !costs.isEmpty(),
                currencies, bases,
                mixedCurrency ? Stat.none() : mean(costs), mixedCurrency ? Stat.none() : percentile(costs, 50),
                costs.isEmpty() || mixedCurrency ? null : costs.stream().mapToDouble(Double::doubleValue).min().getAsDouble(),
                costs.isEmpty() || mixedCurrency ? null : costs.stream().mapToDouble(Double::doubleValue).max().getAsDouble(),
                total == null || succeeded == 0 ? null : total / succeeded,
                costs.isEmpty() || mixedCurrency ? null : directCost,
                infraSamples == 0 || mixedCurrency ? null : infraCost, infraSamples,
                mean(inputTokens), mean(outputTokens),
                mean(latencies), percentile(latencies, 50), percentile(latencies, 95), mean(queueWaits),
                ratio(validationPassed, validationSamples), validationSamples,
                historical);
    }

    public static Stat mean(List<Double> values) {
        return values.isEmpty() ? Stat.none() : new Stat(sum(values) / values.size(), values.size());
    }

    /** Nearest-rank percentile (p in 0..100) of the values. */
    public static Stat percentile(List<Double> values, int p) {
        if (values.isEmpty()) {
            return Stat.none();
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return new Stat(sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1))), sorted.size());
    }

    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private static double sum(List<Double> values) {
        double total = 0;
        for (double value : values) total += value;
        return total;
    }
}
