package com.processVisualisation.virtualKitchen.common.logging;

import org.slf4j.MDC;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Adds up what one action used while it ran, so its closing log line can say so: AI requests, tokens and cost,
 * database reads and writes, cache hits and reused artifacts.
 * <p>
 * Usage is attributed to the current {@linkplain #currentKey() key}: the {@code jobId} MDC key inside a background
 * job (carried onto worker threads by {@code ThreadPoolTaskPool}), otherwise the HTTP request's {@code requestId}.
 * The job's {@code OperationLog}, or {@code RequestIdFilter} for a plain request, {@link #drain drains} it at the end.
 * <p>
 * Static so that low-level code (the MongoDB command listener, cache lookups) can record without being wired to
 * every service. Entries whose owner never drains them (a thread died) are evicted oldest first.
 */
public final class UsageLedger {

    /** Counters other than AI usage. */
    public enum Counter { DB_READS, DB_WRITES, CACHE_HITS, ARTIFACTS_REUSED }

    private static final int MAX_OPEN = 2_000;
    private static final String REQUEST_PREFIX = "req:";

    private static final Map<String, Usage> OPEN = new ConcurrentHashMap<>();
    private static final Queue<String> ORDER = new ConcurrentLinkedQueue<>();

    /** Display-only conversion for log lines; set from {@code ai.request.usd-to-inr} at startup. */
    private static volatile double usdToInr = 88.0;

    private UsageLedger() {
    }

    public static void configureUsdToInr(double rate) {
        usdToInr = rate;
    }

    public static double usdToInr() {
        return usdToInr;
    }

    /** The key usage is attributed to right now: the job id, else {@code req:<requestId>}, else null. */
    public static String currentKey() {
        String jobId = MDC.get(MdcKeys.JOB_ID);
        if (jobId != null && !jobId.isBlank()) {
            return jobId;
        }
        return requestKey(MDC.get(MdcKeys.REQUEST_ID));
    }

    /** The key a plain HTTP request's usage is kept under. */
    public static String requestKey(String requestId) {
        return requestId == null || requestId.isBlank() ? null : REQUEST_PREFIX + requestId;
    }

    /** Counts one event against the current key; outside any job or request it is not counted. */
    public static void increment(Counter counter) {
        add(currentKey(), Usage.NONE.with(counter));
    }

    /**
     * Attributes one AI request's usage to {@code key}.
     *
     * @param costUsd estimated cost, or null when the model has no prices configured (unknown, not free)
     */
    public static void addAi(String key, long inputTokens, long cachedTokens, long outputTokens, Double costUsd) {
        add(key, new Usage(1, inputTokens, cachedTokens, outputTokens, costUsd == null ? 0 : costUsd,
                costUsd == null ? 1 : 0, 0, 0, 0, 0));
    }

    /** Returns and forgets everything attributed to {@code key} ({@link Usage#NONE} if nothing was). */
    public static Usage drain(String key) {
        if (key == null) {
            return Usage.NONE;
        }
        Usage usage = OPEN.remove(key);
        return usage == null ? Usage.NONE : usage;
    }

    private static void add(String key, Usage usage) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (OPEN.merge(key, usage, Usage::plus) == usage) {
            ORDER.add(key);
            evictOldest();
        }
    }

    private static void evictOldest() {
        while (OPEN.size() > MAX_OPEN) {
            String oldest = ORDER.poll();
            if (oldest == null) {
                return;
            }
            OPEN.remove(oldest);
        }
        // Keys already drained stay in ORDER until polled; keep it from growing without bound.
        if (ORDER.size() > MAX_OPEN * 4) {
            ORDER.removeIf(key -> !OPEN.containsKey(key));
        }
    }

    /**
     * @param aiRequests       AI requests made (each may include retries)
     * @param inputTokens      all input tokens, cached included
     * @param outputTokens     output tokens, reasoning tokens included
     * @param costUsd          summed estimated cost of the AI requests that could be priced
     * @param unpricedRequests AI requests whose cost is unknown
     */
    public record Usage(int aiRequests, long inputTokens, long cachedTokens, long outputTokens, double costUsd,
                        int unpricedRequests, long dbReads, long dbWrites, long cacheHits, long artifactsReused) {

        public static final Usage NONE = new Usage(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        Usage with(Counter counter) {
            return new Usage(aiRequests, inputTokens, cachedTokens, outputTokens, costUsd, unpricedRequests,
                    dbReads + (counter == Counter.DB_READS ? 1 : 0),
                    dbWrites + (counter == Counter.DB_WRITES ? 1 : 0),
                    cacheHits + (counter == Counter.CACHE_HITS ? 1 : 0),
                    artifactsReused + (counter == Counter.ARTIFACTS_REUSED ? 1 : 0));
        }

        Usage plus(Usage o) {
            return new Usage(aiRequests + o.aiRequests, inputTokens + o.inputTokens, cachedTokens + o.cachedTokens,
                    outputTokens + o.outputTokens, costUsd + o.costUsd, unpricedRequests + o.unpricedRequests,
                    dbReads + o.dbReads, dbWrites + o.dbWrites, cacheHits + o.cacheHits,
                    artifactsReused + o.artifactsReused);
        }

        /** {@code aiRequests=.. inputTokens=.. outputTokens=.. costUsd=.. costInr=..}. */
        public String aiLogFields() {
            return "aiRequests=" + aiRequests
                    + " inputTokens=" + inputTokens
                    + " outputTokens=" + outputTokens
                    + " costUsd=" + formatCost(costUsd, unpricedRequests, aiRequests, 1, 6)
                    + " costInr=" + formatCost(costUsd, unpricedRequests, aiRequests, usdToInr, 4);
        }

        /** {@code dbReads=.. dbWrites=.. cacheHits=.. artifactsReused=..}. */
        public String resourceLogFields() {
            return "dbReads=" + dbReads + " dbWrites=" + dbWrites
                    + " cacheHits=" + cacheHits + " artifactsReused=" + artifactsReused;
        }

        /** Everything the action used, for its closing log line. */
        public String toLogFields() {
            return aiLogFields() + " " + resourceLogFields();
        }
    }

    /**
     * A cost for a log field: {@code unpriced} when no request could be priced, a lower bound ({@code >=}) when only
     * some could, the plain figure otherwise.
     */
    public static String formatCost(double costUsd, int unpricedRequests, int requests, double rate, int decimals) {
        if (requests > 0 && unpricedRequests >= requests) {
            return "unpriced";
        }
        String bound = unpricedRequests > 0 ? ">=" : "";
        return bound + String.format("%." + decimals + "f", costUsd * rate);
    }
}
