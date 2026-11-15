package com.processVisualisation.virtualKitchen.ai.platform.usage;

import com.processVisualisation.virtualKitchen.common.logging.UsageLedger;

/**
 * The AI side of {@link UsageLedger}: adds up the AI spend of one operation (a background job, or a plain HTTP
 * request when no job is running) across every AI request it makes, so the operation's closing log line and its
 * persisted record can state its total tokens and cost.
 * <p>
 * {@code AiRequestQueueService} records each request under the key current when the request was submitted
 * ({@link UsageLedger#currentKey()}); the operation drains its totals when it ends.
 */
public final class AiCostLedger {

    private AiCostLedger() {
    }

    public static void configureUsdToInr(double rate) {
        UsageLedger.configureUsdToInr(rate);
    }

    public static double usdToInr() {
        return UsageLedger.usdToInr();
    }

    /** Attributes one finished (or failed) AI request's usage to {@code operationId}; a null id is ignored. */
    public static void record(String operationId, AiUsageSummary usage) {
        if (usage == null) {
            return;
        }
        UsageLedger.addAi(operationId, usage.promptTokens(), usage.cachedTokens(),
                usage.completionTokens() + usage.thoughtsTokens(), usage.estimatedCostUsd());
    }

    /**
     * Returns and forgets everything attributed to {@code operationId} ({@link Totals#NONE} if nothing was),
     * including its non-AI counters. Use {@link UsageLedger#drain} directly to keep those.
     */
    public static Totals drain(String operationId) {
        return Totals.of(UsageLedger.drain(operationId));
    }

    /**
     * Persisted on job records as {@code aiUsage}: keep the field names stable.
     *
     * @param requests          AI requests made (each may include retries)
     * @param inputTokens       all input tokens, cached included
     * @param outputTokens      output tokens, reasoning tokens included
     * @param costUsd           summed estimated cost of the requests that could be priced
     * @param unpricedRequests  requests whose model has no configured prices (their cost is unknown, not zero)
     */
    public record Totals(int requests, long inputTokens, long cachedTokens, long outputTokens, double costUsd,
                         int unpricedRequests) {

        public static final Totals NONE = new Totals(0, 0, 0, 0, 0, 0);

        public static Totals of(UsageLedger.Usage usage) {
            if (usage.aiRequests() == 0) {
                return NONE;
            }
            return new Totals(usage.aiRequests(), usage.inputTokens(), usage.cachedTokens(), usage.outputTokens(),
                    usage.costUsd(), usage.unpricedRequests());
        }

        /**
         * {@code aiRequests=.. inputTokens=.. outputTokens=.. costUsd=.. costInr=..}, for an operation's log line.
         * Cost is a lower bound ({@code >=}) when some requests could not be priced, {@code unpriced} when none could.
         */
        public String toLogFields() {
            return "aiRequests=" + requests
                    + " inputTokens=" + inputTokens
                    + " outputTokens=" + outputTokens
                    + " costUsd=" + UsageLedger.formatCost(costUsd, unpricedRequests, requests, 1, 6)
                    + " costInr=" + UsageLedger.formatCost(costUsd, unpricedRequests, requests, UsageLedger.usdToInr(), 4);
        }
    }
}
