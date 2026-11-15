package com.processVisualisation.virtualKitchen.ai.platform.logging;

import com.processVisualisation.virtualKitchen.ai.platform.usage.AiCostLedger;
import com.processVisualisation.virtualKitchen.common.logging.FailureLogger;
import com.processVisualisation.virtualKitchen.common.logging.LogScope;
import com.processVisualisation.virtualKitchen.common.logging.MdcKeys;
import com.processVisualisation.virtualKitchen.common.logging.UsageLedger;
import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * The start/end pair every background operation writes to {@code application.log}, so its whole lifecycle reads
 * as two lines:
 * <pre>
 *   event=&lt;name&gt;_started   &lt;what it is about&gt;
 *   event=&lt;name&gt;_completed durationMs=.. &lt;outcome&gt; aiRequests=.. inputTokens=.. outputTokens=.. costUsd=.. costInr=..
 *                              dbReads=.. dbWrites=.. cacheHits=.. artifactsReused=..
 *   event=&lt;name&gt;_failed    durationMs=.. &lt;outcome&gt; ...same usage fields... errorType=.. error="reason" (+ stack for bugs)
 * </pre>
 * The usage figures are everything {@link UsageLedger} attributed to the operation's {@code jobId} while it ran.
 * Intermediate steps belong at DEBUG, which goes to {@code debug.log} only.
 * <p>
 * Binds the operation id as the thread's {@code jobId} until {@link #close()}, which puts back the previous one; a job
 * started inside another records the outer one as {@code parentJobId}. Callers close it in a {@code finally}.
 * <p>
 * Never put user content (prompts, recipe text) or secrets in the detail strings: ids, counts and sizes only.
 */
public final class OperationLog implements AutoCloseable {

    private final Logger log;
    private final String name;
    private final String operationId;
    private final LogScope scope;
    private final long startedAt = System.nanoTime();
    private UsageLedger.Usage usage;

    private OperationLog(Logger log, String name, String operationId, LogScope scope) {
        this.log = log;
        this.name = name;
        this.operationId = operationId;
        this.scope = scope;
    }

    /**
     * Logs {@code event=<name>_started} and binds {@code operationId} as this thread's {@code jobId} MDC key (worker
     * threads inherit it through {@code ThreadPoolTaskPool}), so every line and AI request of the operation carries it.
     *
     * @param details {@code key=value} context such as ids and sizes, or empty
     */
    public static OperationLog start(Logger log, String name, String operationId, String details) {
        LogScope scope = null;
        if (operationId != null) {
            String outer = MDC.get(MdcKeys.JOB_ID);
            String parent = outer != null && !outer.equals(operationId) ? outer : MDC.get(MdcKeys.PARENT_JOB_ID);
            scope = LogScope.with(MdcKeys.JOB_ID, operationId, MdcKeys.PARENT_JOB_ID, parent);
        }
        log.info("event={}_started {}", name, details == null ? "" : details);
        return new OperationLog(log, name, operationId, scope);
    }

    /** Logs {@code event=<name>_completed} at INFO with duration, {@code details} and what the operation used. */
    public void completed(String details) {
        log.info("event={}_completed durationMs={} {}{}", name, elapsedMs(), prefix(details), usage().toLogFields());
    }

    /** Like {@link #completed} but at WARN, for an operation that finished with some parts failed. */
    public void completedWithErrors(String details) {
        log.warn("event={}_completed durationMs={} {}{}", name, elapsedMs(), prefix(details), usage().toLogFields());
    }

    /**
     * Logs {@code event=<name>_failed} with the reason, at the level {@link FailureLogger} picks for the error: WARN
     * for business outcomes, ERROR for known external failures, ERROR with the stack trace for anything else.
     */
    public void failed(Throwable error, String details) {
        FailureLogger.logFailure(log, name + "_failed",
                error, "durationMs=" + elapsedMs() + " " + prefix(details) + usage().toLogFields());
    }

    /** The AI spend attributed to this operation so far (drained once); safe to call before persisting it. */
    public AiCostLedger.Totals aiTotals() {
        return AiCostLedger.Totals.of(usage());
    }

    /** Everything attributed to this operation, drained once on first use. */
    public UsageLedger.Usage usage() {
        if (usage == null) {
            usage = UsageLedger.drain(operationId);
        }
        return usage;
    }

    public long elapsedMs() {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    /** Puts back the {@code jobId}/{@code parentJobId} the thread had before {@link #start}. */
    @Override
    public void close() {
        if (scope != null) {
            scope.close();
        }
    }

    private static String prefix(String details) {
        return details == null || details.isBlank() ? "" : details.trim() + " ";
    }
}
