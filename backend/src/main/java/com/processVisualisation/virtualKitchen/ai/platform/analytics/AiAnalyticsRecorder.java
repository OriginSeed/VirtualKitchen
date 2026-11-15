package com.processVisualisation.virtualKitchen.ai.platform.analytics;

import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AiRequestAnalytics;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AiRequestAnalytics.Attempt;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AiRequestAnalytics.OutputRef;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AiRequestAnalytics.StatusChange;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AiRequestAnalytics.Usage;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AnalyticsRequestType;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AnalyticsSource;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AnalyticsStatus;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.OperationContext;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.UsageBasis;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.pricing.CostCalculator;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.pricing.PricingRateCard;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.pricing.PricingRegistry;
import com.processVisualisation.virtualKitchen.ai.platform.dispatch.LoggingAiClient;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import com.processVisualisation.virtualKitchen.ai.platform.usage.AiCallListener;
import com.processVisualisation.virtualKitchen.ai.platform.usage.AiCallRecord;
import com.processVisualisation.virtualKitchen.common.logging.MdcKeys;
import com.processVisualisation.virtualKitchen.common.logging.LogScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The only writer of {@code ai_request_analytics}. Called by the existing AI execution paths (the request queue,
 * the provider wrappers via {@link RequestTracker}, chat, and the artifact consumers) as a request moves through
 * its lifecycle: {@link #begin} (before anything else happens) → {@link #modelResolved} → one attempt per provider
 * call → {@link #complete}.
 * <p>
 * Guarantees:
 * <ul>
 *     <li><b>Never throws to its callers.</b> An analytics failure must not fail an AI request.</li>
 *     <li><b>Idempotent, race-free updates.</b> Every update re-reads the record, applies an idempotent change,
 *     recomputes the roll-ups from the attempt list, and writes back with a compare-and-set on the revision —
 *     so concurrent attempts, a late attempt after a timeout, and duplicate completions all converge.</li>
 *     <li><b>Observable failures.</b> A failed write is logged ({@code event=ai_analytics_write_failed}), counted
 *     in {@link AnalyticsHealth}, and queued for retry; a write that is finally given up on is counted as dropped.</li>
 * </ul>
 * Writes are synchronous single-document operations (a few ms against a provider call of seconds).
 */
@Service
public class AiAnalyticsRecorder {

    private static final Logger log = LoggerFactory.getLogger(AiAnalyticsRecorder.class);

    public static final String WORKFLOW_ID_MDC = MdcKeys.WORKFLOW_ID;

    private static final int MAX_CAS_ATTEMPTS = 32;
    private static final int MAX_PENDING = 10_000;
    private static final int MAX_RETRIES = 20;

    private final AiAnalyticsStore store;
    private final PricingRegistry pricing;
    private final TextOutputSink textOutputs;
    private final CorrelationResolver correlationResolver;
    private final AnalyticsHealth health;
    private final Clock clock;
    private final boolean enabled;

    private final ConcurrentLinkedDeque<PendingWrite> pending = new ConcurrentLinkedDeque<>();

    @Autowired
    public AiAnalyticsRecorder(AiAnalyticsStore store, PricingRegistry pricing, TextOutputSink textOutputs,
                               CorrelationResolver correlationResolver, AnalyticsHealth health) {
        this(store, pricing, textOutputs, correlationResolver, health, Clock.systemUTC());
    }

    public AiAnalyticsRecorder(AiAnalyticsStore store, PricingRegistry pricing, TextOutputSink textOutputs,
                               CorrelationResolver correlationResolver, AnalyticsHealth health, Clock clock) {
        this.store = store;
        this.pricing = pricing;
        this.textOutputs = textOutputs;
        this.correlationResolver = correlationResolver;
        this.health = health;
        this.clock = clock;
        this.enabled = store != null;
    }

    /** A recorder that records nothing; for callers (mostly tests) constructed without analytics. */
    public static AiAnalyticsRecorder disabled() {
        return new AiAnalyticsRecorder(null, null, null, null, new AnalyticsHealth(), Clock.systemUTC());
    }

    /**
     * The cost of one successful request to a model priced per output rather than per token (image models priced
     * per image, free local engines, speech priced at zero per second), from the rate card in force now; null when
     * that cannot be known here (no card, a failed call, or a non-zero per-second rate with no duration at hand).
     * Used for log lines and operation totals only; analytics prices every call itself.
     */
    public Double flatCostUsd(String modelKey, AiCapability capability, boolean succeeded) {
        if (pricing == null || modelKey == null || !succeeded) {
            return null;
        }
        PricingRateCard card = pricing.rateFor(modelKey, clock.instant()).orElse(null);
        if (card == null) {
            return null;
        }
        if (card.isLocal()) {
            return 0d;
        }
        if (capability == AiCapability.TEXT_TO_IMAGE && card.getPerImage() != null) {
            return card.getPerImage();
        }
        if (capability == AiCapability.TEXT_TO_SPEECH && card.getPerAudioSecond() != null && card.getPerAudioSecond() == 0d) {
            return 0d;
        }
        return null;
    }

    /**
     * What is known about a request before it runs.
     *
     * @param id an id to use (e.g. the {@code AiRequestJob} id), or null to generate one
     */
    public record Begin(String id, Long userId, AiCapability capability, OperationContext context,
                        String correlationType, String correlationId, String idempotencyKey,
                        String requestedModelKey, String poolName, Long timeoutMs) {
    }

    // --- lifecycle --------------------------------------------------------------------------------------------

    /** Persists the initial QUEUED record and returns its id. Reads the job/workflow ids from the caller's MDC. */
    public String begin(Begin begin) {
        String id = begin.id() != null ? begin.id() : UUID.randomUUID().toString();
        if (!enabled) {
            return id;
        }
        try {
            Instant now = clock.instant();
            AiRequestAnalytics record = new AiRequestAnalytics();
            record.setId(id);
            record.setSource(AnalyticsSource.LIVE);
            record.setUserId(begin.userId());
            record.setRequestType(requestType(begin.capability()));
            OperationContext context = begin.context() != null
                    ? begin.context() : OperationContext.fromCorrelationType(begin.correlationType());
            record.setOperationContext(context);
            record.setCorrelationType(begin.correlationType());
            record.setCorrelationId(begin.correlationId());
            record.setIdempotencyKey(begin.idempotencyKey());
            record.setRequestedModelKey(begin.requestedModelKey());
            record.setPoolName(begin.poolName());
            record.setTimeoutMs(begin.timeoutMs());
            record.setJobId(MDC.get(MdcKeys.JOB_ID));
            record.setWorkflowId(MDC.get(WORKFLOW_ID_MDC));
            correlate(record);
            record.setStatus(AnalyticsStatus.QUEUED);
            record.getStatusHistory().add(StatusChange.of(AnalyticsStatus.QUEUED, now));
            record.setCreatedAt(now);
            record.setQueuedAt(now);
            record.setUpdatedAt(now);
            AnalyticsTotals.recompute(record);
            try {
                store.insertIfAbsent(record);
            } catch (Exception e) {
                onWriteFailure("begin", id, () -> store.insertIfAbsent(record), e);
            }
        } catch (Exception e) {
            // Building the record itself failed (a bug, not a store outage): nothing to retry, but never silent.
            health.failure("begin", e);
            log.error("event=ai_analytics_write_failed operation=begin analyticsId={} errorType={}",
                    id, e.getClass().getSimpleName(), e);
        }
        return id;
    }

    /** A listener to attach to the request's {@code AiUsage}, so every provider call is recorded as an attempt. */
    public RequestTracker tracker(String id, OperationContext context) {
        return new RequestTracker(id, context);
    }

    /** The model that will serve the request (after any credit fallback), and the queue position it was admitted at. */
    public void modelResolved(String id, ModelDefinition model, boolean usedFallback, String fallbackReason,
                              Integer creditCost, String creditTransactionId, String aiRequestJobId, Integer queueDepth) {
        Instant now = clock.instant();
        mutate(id, "model_resolved", record -> {
            record.setAiRequestJobId(aiRequestJobId);
            applyModel(record, model);
            record.setUsedFallback(usedFallback);
            record.setFallbackReason(fallbackReason);
            record.setCreditCost(creditCost);
            record.setCreditTransactionId(creditTransactionId);
            record.setQueueDepthAtSubmit(queueDepth);
            if (record.getStartedAt() == null) {
                record.setStartedAt(now);
            }
            if (record.getStatus() == AnalyticsStatus.QUEUED) {
                record.setStatus(AnalyticsStatus.RUNNING);
                record.getStatusHistory().add(StatusChange.of(AnalyticsStatus.RUNNING, now));
            }
        });
    }

    /**
     * Terminal status. A second completion is a no-op, so duplicate completion events cannot change an outcome.
     *
     * @param transportAttempts queue-level attempts made (1 + retries), or null when not applicable
     * @param workStartedAt     when the work actually began executing (after any pool wait), or null
     */
    public void complete(String id, AnalyticsStatus status, Throwable error, Integer transportAttempts, Instant workStartedAt) {
        Instant now = clock.instant();
        mutate(id, "complete", record -> {
            if (record.getStatus() != null && record.getStatus().isTerminal()) {
                return;
            }
            record.setStatus(status);
            record.getStatusHistory().add(StatusChange.of(status, now));
            record.setCompletedAt(now);
            if (transportAttempts != null) {
                record.setTransportAttempts(transportAttempts);
            }
            if (workStartedAt != null) {
                record.setStartedAt(workStartedAt);
            }
            if (error != null) {
                String category = AnalyticsErrors.category(error);
                record.setErrorCategory(category);
                record.setErrorMessage(AnalyticsErrors.sanitize(error.getMessage()));
                record.setRetryable(AnalyticsErrors.retryable(category));
            }
            applyTimings(record);
        });
    }

    /** Terminal status for a failure: TIMED_OUT, REJECTED or FAILED depending on the error. */
    public void fail(String id, Throwable error, Integer transportAttempts, Instant workStartedAt) {
        complete(id, AnalyticsErrors.terminalStatus(error), error, transportAttempts, workStartedAt);
    }

    /**
     * Closes a record whose completion was never recorded (lost write, or the process stopped mid-request), using
     * the queue job's own final status when there is one.
     *
     * @param jobStatus the {@code ai_request_jobs} status, or null when there is no job
     */
    public void reconcileStale(String id, String jobStatus, String jobError) {
        Instant now = clock.instant();
        mutate(id, "reconcile", record -> {
            if (record.getStatus() != null && record.getStatus().isTerminal()) {
                return;
            }
            AnalyticsStatus status = "COMPLETED".equals(jobStatus) ? AnalyticsStatus.SUCCEEDED : AnalyticsStatus.FAILED;
            record.setStatus(status);
            record.getStatusHistory().add(StatusChange.of(status, now));
            if (status == AnalyticsStatus.FAILED) {
                boolean jobFailed = "FAILED".equals(jobStatus);
                record.setErrorCategory(jobFailed ? record.getErrorCategory() : "ABANDONED");
                record.setErrorMessage(jobFailed ? AnalyticsErrors.sanitize(jobError)
                        : "Never completed; closed by the analytics reconciler");
            }
            if (!record.getMissingFields().contains("completion")) {
                record.getMissingFields().add("completion");
            }
            // The real completion time is unknown, so no completion-based timings are invented.
        });
    }

    /** The request was served from the artifact store: no provider call, no credits. */
    public void reused(String id, String artifactId, String producingRequestId, ModelDefinition producedBy,
                       boolean usedFallback) {
        mutate(id, "reused", record -> {
            record.setArtifactId(artifactId);
            record.setReusedFromRequestId(producingRequestId);
            applyModel(record, producedBy);
            record.setUsedFallback(usedFallback);
        });
        complete(id, AnalyticsStatus.REUSED, null, 0, null);
    }

    public void linkArtifact(String id, String artifactId) {
        mutate(id, "link_artifact", record -> record.setArtifactId(artifactId));
    }

    /** Points the request at where its result lives, unless it already points somewhere (first writer wins). */
    public void linkOutput(String id, OutputRef ref) {
        if (id == null || ref == null) {
            return;
        }
        mutate(id, "link_output", record -> {
            if (record.getOutputRef() == null) {
                record.setOutputRef(ref);
            }
        });
    }

    /**
     * Records the exact storage URL an image/audio request produced, as soon as its upload succeeds, so the result
     * stays inspectable even after the asset is regenerated. Keeps the first URL recorded for a request.
     */
    public void outputStored(String requestId, String kind, String collection, String assetId, String url,
                             String mimeType, Long sizeBytes) {
        if (requestId == null || url == null) {
            return;
        }
        Instant now = clock.instant();
        mutate(requestId, "output_stored", record -> {
            if (record.getOutputRef() != null && record.getOutputRef().getUrl() != null) {
                return;
            }
            OutputRef ref = record.getOutputRef() != null ? record.getOutputRef() : new OutputRef();
            ref.setKind(kind);
            ref.setCollection(collection);
            ref.setId(assetId);
            ref.setUrl(url);
            ref.setMimeType(mimeType);
            ref.setSizeBytes(sizeBytes);
            ref.setStoredAt(now);
            record.setOutputRef(ref);
        });
    }

    /** Analytics writes still waiting for a retry. */
    public int pendingWrites() {
        return pending.size();
    }

    // --- per-call tracking --------------------------------------------------------------------------------------

    /** Records each provider call of one request as an attempt; see {@link AiCallListener}. */
    public final class RequestTracker implements AiCallListener {

        private final String requestId;
        private final OperationContext context;
        private final AtomicInteger callIndex = new AtomicInteger();
        private volatile ModelDefinition model;
        private volatile int transportAttempt = 1;
        private volatile Instant workStartedAt;
        private volatile String lastAttemptId;
        private volatile String lastTextOutputId;

        private RequestTracker(String requestId, OperationContext context) {
            this.requestId = requestId;
            this.context = context;
        }

        public String requestId() {
            return requestId;
        }

        public void model(ModelDefinition model) {
            this.model = model;
        }

        public void transportAttempt(int attempt) {
            this.transportAttempt = attempt;
        }

        /** Marks when the work began executing; the first call wins (later transport retries keep the original). */
        public void markWorkStarted() {
            if (workStartedAt == null) {
                workStartedAt = clock.instant();
            }
        }

        public Instant workStartedAt() {
            return workStartedAt;
        }

        @Override
        public String lastTextOutputId() {
            return lastTextOutputId;
        }

        @Override
        public void onCall(AiCallRecord call) {
            if (!enabled) {
                return;
            }
            try {
                Attempt attempt = toAttempt(call, callIndex.incrementAndGet(), transportAttempt, model);
                lastAttemptId = attempt.getAttemptId();
                lastTextOutputId = null;
                if (call.isSuccess() && call.getOutputText() != null) {
                    try {
                        String outputId = textOutputs.save(requestId, attempt.getAttemptId(), context, call);
                        attempt.setOutputId(outputId);
                        lastTextOutputId = outputId;
                    } catch (Exception e) {
                        health.failure("text_output", e);
                        log.warn("event=ai_analytics_write_failed operation=text_output analyticsId={} errorType={}",
                                requestId, e.getClass().getSimpleName());
                    }
                }
                mutate(requestId, "attempt", record -> addAttempt(record, attempt));
                if (attempt.getOutputId() != null) {
                    linkOutput(requestId, outputRef("TEXT", "ai_responses", attempt.getOutputId()));
                }
            } catch (Exception e) {
                health.failure("attempt", e);
                log.error("event=ai_analytics_write_failed operation=attempt analyticsId={} errorType={}",
                        requestId, e.getClass().getSimpleName(), e);
            }
        }

        @Override
        public void onValidation(boolean passed, List<String> errors) {
            String attemptId = lastAttemptId;
            List<String> capped = errors == null ? null
                    : errors.stream().limit(10).map(AnalyticsErrors::sanitize).toList();
            mutate(requestId, "validation", record -> {
                record.setValidationPassed(passed);
                for (Attempt attempt : record.getAttempts()) {
                    if (attempt.getAttemptId().equals(attemptId)) {
                        attempt.setValidationPassed(passed);
                        attempt.setValidationErrors(capped == null || capped.isEmpty() ? null : capped);
                    }
                }
                // The accepted output, not merely the latest one, is the request's result.
                if (passed) {
                    record.getAttempts().stream()
                            .filter(a -> a.getAttemptId().equals(attemptId) && a.getOutputId() != null)
                            .findFirst()
                            .ifPresent(a -> record.setOutputRef(outputRef("JSON", "ai_responses", a.getOutputId())));
                }
            });
        }
    }

    // --- internals ----------------------------------------------------------------------------------------------

    private Attempt toAttempt(AiCallRecord call, int index, int transportAttempt, ModelDefinition model) {
        Attempt attempt = new Attempt();
        attempt.setAttemptId(UUID.randomUUID().toString());
        attempt.setCallIndex(index);
        attempt.setTransportAttempt(transportAttempt);
        attempt.setPurpose(call.getPurpose());
        attempt.setProvider(call.getProvider());
        attempt.setProviderModelId(call.getProviderModelId());
        attempt.setReportedModel(call.getReportedModel());
        attempt.setStartedAt(call.getStartedAt());
        attempt.setEndedAt(call.getEndedAt());
        attempt.setDurationMs(call.getDurationMs());
        attempt.setStatus(call.isSuccess() ? AnalyticsStatus.SUCCEEDED : AnalyticsStatus.FAILED);
        attempt.setFinishReason(call.getFinishReason());
        attempt.setHttpStatus(call.getHttpStatus());
        if (!call.isSuccess()) {
            attempt.setErrorCategory(AnalyticsErrors.category(call.getErrorType(), call.getHttpStatus()));
            attempt.setErrorMessage(AnalyticsErrors.sanitize(call.getErrorMessage()));
        }
        Usage usage = usageOf(call);
        attempt.setUsage(usage);
        Instant at = call.getStartedAt() != null ? call.getStartedAt() : clock.instant();
        PricingRateCard card = model == null ? null : pricing.rateFor(model.getKey(), at).orElse(null);
        attempt.setCost(CostCalculator.calculate(usage, card, call.getDurationMs(), call.isSuccess(), clock.instant()));
        return attempt;
    }

    static Usage usageOf(AiCallRecord call) {
        Usage usage = new Usage();
        usage.setInputTokens(call.getInputTokens());
        usage.setCachedInputTokens(call.getCachedInputTokens());
        usage.setOutputTokens(call.getOutputTokens());
        usage.setReasoningTokens(call.getReasoningTokens());
        usage.setTotalTokens(call.getTotalTokens());
        usage.setImageCount(call.getImageCount());
        usage.setImageWidth(call.getImageWidth());
        usage.setImageHeight(call.getImageHeight());
        usage.setAudioDurationMs(call.getAudioDurationMs());
        usage.setInputChars(call.getInputChars());
        usage.setOutputChars(call.getOutputChars());
        usage.setOutputBytes(call.getOutputBytes());
        usage.setProviderDurationMs(call.getProviderDurationMs());
        boolean tokens = call.getInputTokens() != null || call.getOutputTokens() != null || call.getTotalTokens() != null;
        usage.setUsageBasis(tokens ? UsageBasis.PROVIDER_REPORTED
                : call.getAudioDurationMs() != null ? UsageBasis.DERIVED_LOCAL : UsageBasis.NOT_REPORTED);
        return usage;
    }

    private static void addAttempt(AiRequestAnalytics record, Attempt attempt) {
        boolean known = record.getAttempts().stream().anyMatch(a -> a.getAttemptId().equals(attempt.getAttemptId()));
        if (known) {
            return;
        }
        attempt.setLate(record.getStatus() != null && record.getStatus().isTerminal());
        record.getAttempts().add(attempt);
    }

    private static void applyModel(AiRequestAnalytics record, ModelDefinition model) {
        if (model == null) {
            return;
        }
        record.setResolvedModelKey(model.getKey());
        record.setModelTier(model.getTier() == null ? null : model.getTier().name());
        if (record.getFinalProvider() == null) {
            record.setFinalProvider(LoggingAiClient.providerOf(model.getProviderBean()));
        }
        if (record.getFinalProviderModelId() == null) {
            record.setFinalProviderModelId(model.getProviderModelId());
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("key", model.getKey());
        snapshot.put("capability", model.getCapability() == null ? null : model.getCapability().name());
        snapshot.put("tier", model.getTier() == null ? null : model.getTier().name());
        snapshot.put("provider", LoggingAiClient.providerOf(model.getProviderBean()));
        snapshot.put("providerModelId", model.getProviderModelId());
        snapshot.put("creditCost", model.getCreditCost());
        snapshot.put("fallbackModelKey", model.getFallbackModelKey());
        snapshot.put("inputUsdPerMillion", model.getInputUsdPerMillion());
        snapshot.put("outputUsdPerMillion", model.getOutputUsdPerMillion());
        snapshot.put("cachedInputUsdPerMillion", model.getCachedInputUsdPerMillion());
        record.setModelSnapshot(snapshot);
    }

    static void applyTimings(AiRequestAnalytics record) {
        record.setQueueWaitMs(between(record.getQueuedAt(), record.getStartedAt()));
        record.setExecutionMs(between(record.getStartedAt(), record.getCompletedAt()));
        record.setTotalMs(between(record.getQueuedAt(), record.getCompletedAt()));
    }

    private static Long between(Instant from, Instant to) {
        if (from == null || to == null) {
            return null;
        }
        return Math.max(0, Duration.between(from, to).toMillis());
    }

    private void correlate(AiRequestAnalytics record) {
        try {
            CorrelationResolver.Correlation correlation = correlationResolver.resolve(
                    record.getOperationContext(), record.getJobId(), record.getCorrelationId(), record.getWorkflowId());
            record.setRecipeId(correlation.recipeId());
            record.setProcessId(correlation.processId());
            record.setStepId(correlation.stepId());
            record.setWorkflowId(correlation.workflowId());
        } catch (Exception e) {
            // Correlation is best effort; the identifiers simply stay null.
            log.debug("event=ai_analytics_correlation_failed analyticsId={} errorType={}",
                    record.getId(), e.getClass().getSimpleName());
        }
    }

    static OutputRef outputRef(String kind, String collection, String id) {
        OutputRef ref = new OutputRef();
        ref.setKind(kind);
        ref.setCollection(collection);
        ref.setId(id);
        return ref;
    }

    static AnalyticsRequestType requestType(AiCapability capability) {
        if (capability == null) {
            return AnalyticsRequestType.UNKNOWN;
        }
        return switch (capability) {
            case TEXT_TO_TEXT -> AnalyticsRequestType.TEXT_TO_TEXT;
            case TEXT_TO_IMAGE -> AnalyticsRequestType.TEXT_TO_IMAGE;
            case TEXT_TO_SPEECH -> AnalyticsRequestType.TEXT_TO_AUDIO;
        };
    }

    private void mutate(String id, String operation, Consumer<AiRequestAnalytics> change) {
        if (id == null || !enabled) {
            return;
        }
        try {
            applyWithCas(id, change);
        } catch (Exception e) {
            onWriteFailure(operation, id, () -> applyWithCas(id, change), e);
        }
    }

    private void applyWithCas(String id, Consumer<AiRequestAnalytics> change) {
        for (int i = 0; i < MAX_CAS_ATTEMPTS; i++) {
            AiRequestAnalytics record = store.findById(id)
                    .orElseThrow(() -> new IllegalStateException("No analytics record " + id + " (yet)"));
            long revision = record.getRevision();
            change.accept(record);
            AnalyticsTotals.recompute(record);
            record.setRevision(revision + 1);
            record.setUpdatedAt(clock.instant());
            if (store.replaceIfRevision(record, revision)) {
                return;
            }
            Thread.yield();
        }
        throw new IllegalStateException("Analytics record " + id + " kept changing under concurrent writes");
    }

    private void onWriteFailure(String operation, String id, Runnable retry, Exception error) {
        health.failure(operation, error);
        if (pending.size() >= MAX_PENDING) {
            health.dropped();
            log.error("event=ai_analytics_write_dropped operation={} analyticsId={} reason=retry_buffer_full errorType={}",
                    operation, id, error.getClass().getSimpleName());
            return;
        }
        pending.add(new PendingWrite(operation, id, retry, new AtomicInteger()));
        log.warn("event=ai_analytics_write_failed operation={} analyticsId={} queuedForRetry=true errorType={} error=\"{}\"",
                operation, id, error.getClass().getSimpleName(), AnalyticsErrors.sanitize(error.getMessage()));
    }

    /** Re-applies queued writes in order. Each write is idempotent, so a retry can never double-apply. */
    @Scheduled(fixedDelayString = "${vk.ai.analytics.retry-interval-ms:30000}")
    public void retryPendingWrites() {
        if (pending.isEmpty()) {
            return;
        }
        // Scheduler threads have no request context; name this sweep so its lines group together.
        try (LogScope ignored = LogScope.with(MdcKeys.AREA, "system", MdcKeys.ACTION, "scheduled.analytics_write_retry",
                MdcKeys.JOB_ID, "analytics-retry-" + Long.toHexString(System.currentTimeMillis()))) {
            retryPendingWritesNow();
        }
    }

    private void retryPendingWritesNow() {
        int batch = pending.size();
        List<PendingWrite> again = new ArrayList<>();
        for (int i = 0; i < batch; i++) {
            PendingWrite write = pending.poll();
            if (write == null) {
                break;
            }
            try {
                write.action().run();
                health.retrySucceeded();
            } catch (Exception e) {
                if (write.tries().incrementAndGet() >= MAX_RETRIES) {
                    health.dropped();
                    log.error("event=ai_analytics_write_dropped operation={} analyticsId={} reason=retries_exhausted errorType={}",
                            write.operation(), write.id(), e.getClass().getSimpleName());
                } else {
                    again.add(write);
                }
            }
        }
        // Back at the front, in their original order, ahead of anything queued meanwhile.
        for (int i = again.size() - 1; i >= 0; i--) {
            pending.addFirst(again.get(i));
        }
    }

    private record PendingWrite(String operation, String id, Runnable action, AtomicInteger tries) {
    }
}
