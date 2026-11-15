package com.processVisualisation.virtualKitchen.ai.platform.queue;

import com.processVisualisation.virtualKitchen.ai.platform.analytics.AiAnalyticsRecorder;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AnalyticsStatus;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.OperationContext;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifact;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifactService;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifactSpec;
import com.processVisualisation.virtualKitchen.ai.platform.credit.CreditService;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelRegistry;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import com.processVisualisation.virtualKitchen.ai.platform.routing.FallbackReason;
import com.processVisualisation.virtualKitchen.ai.platform.routing.ModelSelectionOutcome;
import com.processVisualisation.virtualKitchen.ai.platform.routing.ModelSelectionService;
import com.processVisualisation.virtualKitchen.ai.platform.usage.AiCostLedger;
import com.processVisualisation.virtualKitchen.ai.platform.usage.AiUsage;
import com.processVisualisation.virtualKitchen.ai.platform.usage.AiUsageMeter;
import com.processVisualisation.virtualKitchen.ai.platform.usage.AiUsageSummary;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiCommunicationException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiRateLimitedException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiTimeoutException;
import com.processVisualisation.virtualKitchen.common.concurrent.NamedTask;
import com.processVisualisation.virtualKitchen.common.concurrent.TaskPool;
import com.processVisualisation.virtualKitchen.common.concurrent.TaskResult;
import com.processVisualisation.virtualKitchen.common.logging.FailureLogger;
import com.processVisualisation.virtualKitchen.common.logging.UsageLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The admission-control, job-persistence, and retry/backoff layer every AI
 * request goes through, on top of {@link ModelSelectionService} (which model)
 * and {@link CreditService} (whose credits). Two execution modes are offered:
 * <ul>
 *     <li>{@link #executeBounded} submits the work to the dedicated {@code
 *     "ai-text"} {@link TaskPool} and blocks the caller up to a configured
 *     timeout. Used for text-to-text (recipe generation, chat), which today
 *     runs entirely unbounded on the servlet thread.</li>
 *     <li>{@link #executeInline} runs the work directly on the calling
 *     thread, with no nested pool submission. Used for text-to-image, whose
 *     per-step calls already run inside the existing {@code "visualization"}
 *     pool's own bounded worker threads (via {@code RecipeProcessVisualizationJobService})
 *     — submitting to that same pool again from within one of its own
 *     workers would risk a saturated pool deadlocking against itself, exactly
 *     what {@code TaskPoolConfig} already keeps the orchestrator/worker pools
 *     separate to avoid.</li>
 * </ul>
 * Both modes share the same admission check, job lifecycle, and retry policy;
 * only how the work is actually invoked differs.
 * <p>
 * A duplicate submission (same {@code idempotencyKey} for the same user) is
 * deliberately left to surface as the Mongo {@code DuplicateKeyException}
 * that {@code AiRequestJob}'s compound unique index throws — {@code
 * GlobalExceptionHandler} already maps that to HTTP 409, so no reservation
 * or extra job row is ever created for it.
 */
@Service
public class AiRequestQueueService {

    private static final Logger log = LoggerFactory.getLogger(AiRequestQueueService.class);

    private static final String AI_TEXT_POOL = "ai-text";
    private static final String AI_IMAGE_POOL = "visualization";
    private static final String AI_TTS_POOL = "narration";

    private final AiRequestJobRepository jobRepository;
    private final ModelSelectionService modelSelectionService;
    private final CreditService creditService;
    private final TaskPool aiTextTaskPool;
    private final AiQueueProperties queueProperties;
    private final AiRequestProperties requestProperties;
    private final AiArtifactService artifactService;
    private final AiModelRegistry modelRegistry;
    private final AiAnalyticsRecorder analytics;

    private final Map<String, AtomicInteger> inFlightByPool = new ConcurrentHashMap<>();

    private Sleeper sleeper = AiRequestQueueService::sleepQuietly;

    /** Without analytics; for callers (mostly tests) that don't need it. */
    public AiRequestQueueService(
            AiRequestJobRepository jobRepository,
            ModelSelectionService modelSelectionService,
            CreditService creditService,
            TaskPool aiTextTaskPool,
            AiQueueProperties queueProperties,
            AiRequestProperties requestProperties,
            AiArtifactService artifactService,
            AiModelRegistry modelRegistry
    ) {
        this(jobRepository, modelSelectionService, creditService, aiTextTaskPool, queueProperties, requestProperties,
                artifactService, modelRegistry, AiAnalyticsRecorder.disabled());
    }

    @Autowired
    public AiRequestQueueService(
            AiRequestJobRepository jobRepository,
            ModelSelectionService modelSelectionService,
            CreditService creditService,
            @Qualifier("aiTextTaskPool") TaskPool aiTextTaskPool,
            AiQueueProperties queueProperties,
            AiRequestProperties requestProperties,
            AiArtifactService artifactService,
            AiModelRegistry modelRegistry,
            AiAnalyticsRecorder analytics
    ) {
        this.jobRepository = jobRepository;
        this.modelSelectionService = modelSelectionService;
        this.creditService = creditService;
        this.aiTextTaskPool = aiTextTaskPool;
        this.queueProperties = queueProperties;
        this.requestProperties = requestProperties;
        this.artifactService = artifactService;
        this.modelRegistry = modelRegistry;
        this.analytics = analytics;
        AiCostLedger.configureUsdToInr(requestProperties.getUsdToInr());
    }

    /** Bounded, pool-submitted execution — see class javadoc. Used for {@link AiCapability#TEXT_TO_TEXT}. */
    public <R> AiRequestOutcome<R> executeBounded(
            Long userId, AiCapability capability, String preferredModelKey, String idempotencyKey,
            String correlationType, String correlationId, AiWork<R> work
    ) {
        return executeBounded(userId, capability, preferredModelKey, idempotencyKey,
                correlationType, correlationId, null, work);
    }

    /**
     * Bounded execution with AI artifact reuse and staging.
     * <p>
     * Passing an {@code artifactSpec} opts this call site into the artifact store: a stored
     * payload is served instead of calling the provider, and a fresh payload is persisted before
     * this method returns. Passing {@code null} is exactly the previous behaviour.
     *
     * @param artifactSpec the artifact key, codec, and consumer for this call site, or null to opt out
     */
    public <R> AiRequestOutcome<R> executeBounded(
            Long userId, AiCapability capability, String preferredModelKey, String idempotencyKey,
            String correlationType, String correlationId, AiArtifactSpec<R> artifactSpec, AiWork<R> work
    ) {
        long timeoutMs = requestProperties.timeoutFor(capability, 30_000L);
        String analyticsId = analytics.begin(new AiAnalyticsRecorder.Begin(null, userId, capability, null,
                correlationType, correlationId, idempotencyKey, preferredModelKey, AI_TEXT_POOL, timeoutMs));
        return recordingFailures(analyticsId, () -> {
            Optional<AiRequestOutcome<R>> reused = findReusable(userId, artifactSpec, analyticsId);
            if (reused.isPresent()) {
                return reused.get();
            }

            return withAdmissionControl(AI_TEXT_POOL, capability, depth -> {
                AiRequestJob job = createJob(analyticsId, userId, capability, preferredModelKey, idempotencyKey,
                        correlationType, correlationId);
                ModelSelectionOutcome selection = beginProcessing(job, capability, preferredModelKey, depth);
                AiUsage usage = trackedUsage(job, correlationType, selection);

                AiRequestOutcome<R> outcome = runWithRetries(job, selection, usage, () -> {
                // Bound on the pool thread, where the provider calls actually happen.
                CompletableFuture<TaskResult<R>> future = aiTextTaskPool.submit(new NamedTask<>(job.getId(),
                        () -> AiUsageMeter.measure(usage, () -> {
                            markWorkStarted(usage);
                            return work.run(selection);
                        })));
                TaskResult<R> result;
                try {
                    result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (ExecutionException ee) {
                    throw ee.getCause() instanceof Exception ex ? ex : ee;
                }
                if (!result.isSuccess()) {
                    Throwable error = result.error();
                    throw error instanceof Exception ex ? ex : new RuntimeException(error);
                }
                return result.value();
                });

                return withStagedArtifact(userId, capability, correlationType, correlationId, artifactSpec, outcome);
            });
        });
    }

    /**
     * Inline, calling-thread execution — see class javadoc. Used for {@link AiCapability#TEXT_TO_IMAGE}
     * and {@link AiCapability#TEXT_TO_SPEECH} (the latter is admitted against its own depth limit).
     */
    public <R> AiRequestOutcome<R> executeInline(
            Long userId, AiCapability capability, String preferredModelKey, String idempotencyKey,
            String correlationType, String correlationId, AiWork<R> work
    ) {
        return executeInline(userId, capability, preferredModelKey, idempotencyKey,
                correlationType, correlationId, null, work);
    }

    /**
     * Inline execution with AI artifact reuse and staging.
     * <p>
     * Passing an {@code artifactSpec} opts this call site into the artifact store; passing
     * {@code null} is exactly the previous behaviour.
     *
     * @param artifactSpec the artifact key, codec, and consumer for this call site, or null to opt out
     */
    public <R> AiRequestOutcome<R> executeInline(
            Long userId, AiCapability capability, String preferredModelKey, String idempotencyKey,
            String correlationType, String correlationId, AiArtifactSpec<R> artifactSpec, AiWork<R> work
    ) {
        String pool = capability == AiCapability.TEXT_TO_SPEECH ? AI_TTS_POOL : AI_IMAGE_POOL;
        String analyticsId = analytics.begin(new AiAnalyticsRecorder.Begin(null, userId, capability, null,
                correlationType, correlationId, idempotencyKey, preferredModelKey, pool, null));
        return recordingFailures(analyticsId, () -> {
            Optional<AiRequestOutcome<R>> reused = findReusable(userId, artifactSpec, analyticsId);
            if (reused.isPresent()) {
                return reused.get();
            }

            return withAdmissionControl(pool, capability, depth -> {
                AiRequestJob job = createJob(analyticsId, userId, capability, preferredModelKey, idempotencyKey,
                        correlationType, correlationId);
                ModelSelectionOutcome selection = beginProcessing(job, capability, preferredModelKey, depth);

                AiUsage usage = trackedUsage(job, correlationType, selection);
                AiRequestOutcome<R> outcome = runWithRetries(job, selection, usage,
                        () -> AiUsageMeter.measure(usage, () -> {
                            markWorkStarted(usage);
                            return work.run(selection);
                        }));
                return withStagedArtifact(userId, capability, correlationType, correlationId, artifactSpec, outcome);
            });
        });
    }

    /**
     * Records any failure that escapes before or around the provider work (queue full, no model, a duplicate
     * submission) as the request's terminal status, then rethrows it unchanged. A request that already reached a
     * terminal status keeps it: completion is idempotent.
     */
    private <R> AiRequestOutcome<R> recordingFailures(String analyticsId,
                                                       java.util.function.Supplier<AiRequestOutcome<R>> body) {
        try {
            return body.get();
        } catch (RuntimeException e) {
            analytics.fail(analyticsId, e, null, null);
            throw e;
        }
    }

    /** A usage meter for this job that also hands every provider call to the analytics recorder. */
    private AiUsage trackedUsage(AiRequestJob job, String correlationType, ModelSelectionOutcome selection) {
        AiUsage usage = new AiUsage();
        AiAnalyticsRecorder.RequestTracker tracker =
                analytics.tracker(job.getId(), OperationContext.fromCorrelationType(correlationType));
        tracker.model(selection.model());
        usage.setCallListener(tracker);
        return usage;
    }

    private static AiAnalyticsRecorder.RequestTracker trackerOf(AiUsage usage) {
        return usage.getCallListener() instanceof AiAnalyticsRecorder.RequestTracker tracker ? tracker : null;
    }

    private static void markWorkStarted(AiUsage usage) {
        AiAnalyticsRecorder.RequestTracker tracker = trackerOf(usage);
        if (tracker != null) {
            tracker.markWorkStarted();
        }
    }

    /**
     * Read-before-spend: serves a stored payload instead of calling the provider.
     * <p>
     * This runs <b>before</b> {@link #withAdmissionControl} and therefore before
     * {@link #beginProcessing}, which is where {@code ModelSelectionService#select} reserves
     * credits. A reuse check placed any later — inside the {@link AiWork} lambda, say — would
     * still have reserved and consumed a credit, defeating the point of the store.
     * <p>
     * It also sits outside admission control on purpose: a hit performs no provider work, so it
     * must not be rejected with {@link AiQueueFullException} while the queue is saturated. A burst
     * of retried-after-failure requests should be absorbed by the store, not bounced by it.
     * <p>
     * No {@code AiRequestJob} row is written for a hit, so {@code ai_request_jobs} keeps meaning
     * "a provider call we actually made"; reuse is observable via {@code AiArtifact.reuseCount}.
     */
    private <R> Optional<AiRequestOutcome<R>> findReusable(Long userId, AiArtifactSpec<R> artifactSpec, String analyticsId) {
        if (artifactSpec == null) {
            return Optional.empty();
        }
        Optional<AiRequestOutcome<R>> reused = artifactService.findReusable(userId, artifactSpec)
                .map(hit -> AiRequestOutcome.reused(hit, reusedSelection(hit.artifact())));
        reused.ifPresent(outcome -> analytics.reused(analyticsId, outcome.artifact().getId(),
                outcome.artifact().getProducingJobId(), outcome.selection().model(), outcome.selection().usedFallback()));
        return reused;
    }

    /**
     * Rebuilds the model selection for a reused payload from the artifact's recorded metadata.
     * <p>
     * The reservation is always {@code null}: the payload was paid for once, when it was produced,
     * so nothing downstream may consume or release credits against it.
     */
    private ModelSelectionOutcome reusedSelection(AiArtifact artifact) {
        ModelDefinition model = modelRegistry.find(artifact.getProducedByModelKey())
                .orElseGet(() -> syntheticDefinition(artifact));
        return new ModelSelectionOutcome(model, artifact.isUsedFallback(), FallbackReason.NONE, null);
    }

    /**
     * Stands in for a model that has since been removed from configuration, so an artifact
     * produced by it stays reusable rather than being stranded.
     */
    private ModelDefinition syntheticDefinition(AiArtifact artifact) {
        ModelDefinition definition = new ModelDefinition();
        definition.setKey(artifact.getProducedByModelKey());
        definition.setCapability(artifact.getCapability());
        definition.setTier(artifact.getProducedByTier());
        definition.setCreditCost(artifact.getCreditCost());
        definition.setEnabled(false);
        return definition;
    }

    /**
     * Write-before-dependency: persists the payload before the caller — and therefore before any
     * dependent work — can touch it.
     * <p>
     * Staging failures are logged and swallowed. Failing an otherwise-successful <em>paid</em> call
     * in order to report a bookkeeping failure would be strictly worse than the behaviour this
     * feature replaces; the caller simply proceeds without a retained payload.
     */
    private <R> AiRequestOutcome<R> withStagedArtifact(
            Long userId, AiCapability capability, String correlationType, String correlationId,
            AiArtifactSpec<R> artifactSpec, AiRequestOutcome<R> outcome) {

        if (artifactSpec == null) {
            return outcome;
        }
        try {
            AiArtifact artifact = artifactService.stage(userId, capability, correlationType, correlationId,
                    artifactSpec, outcome.value(), outcome.selection(), outcome.jobId());
            analytics.linkArtifact(outcome.jobId(), artifact.getId());
            return AiRequestOutcome.fresh(outcome.jobId(), outcome.value(), outcome.selection(), artifact);
        } catch (Exception e) {
            log.error("event=ai_artifact_stage_failed aiRequestId={} artifactKey={} payloadRetained=false errorType={}",
                    outcome.jobId(), artifactSpec.artifactKey(), e.getClass().getSimpleName(), e);
            return outcome;
        }
    }

    /** Runs {@code body} with the pool's in-flight depth (including this request) once admitted. */
    private <R> AiRequestOutcome<R> withAdmissionControl(String poolName, AiCapability capability,
                                                          java.util.function.IntFunction<AiRequestOutcome<R>> body) {
        AtomicInteger counter = inFlightByPool.computeIfAbsent(poolName, k -> new AtomicInteger());
        int maxDepth = switch (poolName) {
            case AI_TEXT_POOL -> queueProperties.getAiTextMaxDepth();
            case AI_TTS_POOL -> queueProperties.getAiTtsMaxDepth();
            default -> queueProperties.getAiImageMaxDepth();
        };

        int depth = counter.incrementAndGet();
        if (depth > maxDepth) {
            counter.decrementAndGet();
            log.warn("event=ai_request_rejected reason=queue_full pool={} capability={} maxDepth={}",
                    poolName, capability, maxDepth);
            throw new AiQueueFullException(capability);
        }
        try {
            return body.apply(depth);
        } finally {
            counter.decrementAndGet();
        }
    }

    /** @param id the job id, shared with the request's analytics record so the two can always be joined */
    private AiRequestJob createJob(String id, Long userId, AiCapability capability, String preferredModelKey,
                                    String idempotencyKey, String correlationType, String correlationId) {
        AiRequestJob job = new AiRequestJob();
        job.setId(id != null ? id : UUID.randomUUID().toString());
        job.setUserId(userId);
        job.setCapability(capability);
        job.setRequestedModelKey(preferredModelKey);
        job.setIdempotencyKey(idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString());
        job.setStatus(AiRequestStatus.QUEUED);
        job.setAttempt(0);
        job.setMaxAttempts(requestProperties.getMaxRetries() + 1);
        job.setCorrelationType(correlationType);
        job.setCorrelationId(correlationId);
        // The job, or for a plain HTTP request "req:<requestId>": whoever drains the usage ledger at the end.
        job.setOperationId(UsageLedger.currentKey());
        job.setQueuedAt(Instant.now());
        // A DuplicateKeyException here (same userId+idempotencyKey already exists) is intentionally
        // left to propagate to GlobalExceptionHandler's existing 409 handler rather than caught here —
        // see class javadoc.
        return jobRepository.save(job);
    }

    private ModelSelectionOutcome beginProcessing(AiRequestJob job, AiCapability capability, String preferredModelKey,
                                                  int queueDepth) {
        ModelSelectionOutcome selection;
        try {
            selection = modelSelectionService.select(job.getUserId(), capability, preferredModelKey);
        } catch (RuntimeException ex) {
            job.setStatus(AiRequestStatus.FAILED);
            job.setErrorMessage(ex.getMessage());
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);
            throw ex;
        }

        if (selection.usedFallback()) {
            log.warn("event=ai_model_fallback_used aiRequestId={} capability={} requestedModelKey={} modelKey={} fallbackReason={}",
                    job.getId(), capability, preferredModelKey, selection.model().getKey(), selection.fallbackReason());
        } else {
            log.debug("event=ai_model_selected aiRequestId={} capability={} modelKey={} tier={}",
                    job.getId(), capability, selection.model().getKey(), selection.model().getTier());
        }

        job.setResolvedModelKey(selection.model().getKey());
        job.setResolvedTier(selection.model().getTier());
        job.setUsedFallback(selection.usedFallback());
        job.setFallbackReason(selection.fallbackReason().name());
        job.setCreditCost(selection.reservation() != null ? selection.reservation().cost() : 0);
        job.setCreditTransactionId(selection.reservation() != null ? selection.reservation().transactionId() : null);
        job.setStatus(AiRequestStatus.PROCESSING);
        job.setStartedAt(Instant.now());
        job.setAttempt(1);
        jobRepository.save(job);
        analytics.modelResolved(job.getId(), selection.model(), selection.usedFallback(), job.getFallbackReason(),
                job.getCreditCost(), job.getCreditTransactionId(), job.getId(), queueDepth);
        return selection;
    }

    /**
     * @param usage collects the token usage of every provider call across all attempts; it is recorded on the job,
     *              logged, and attached to the CONSUME (success) or RELEASE (failure) ledger entry
     */
    private <R> AiRequestOutcome<R> runWithRetries(
            AiRequestJob job, ModelSelectionOutcome selection, AiUsage usage, Attempt<R> attempt
    ) {
        int transientRetriesLeft = job.getMaxAttempts() - 1;
        int rateLimitRetriesLeft = Math.max(0, requestProperties.getRateLimitMaxRetries());
        int rateLimitRetries = 0;
        Exception lastError = null;

        AiAnalyticsRecorder.RequestTracker tracker = trackerOf(usage);
        for (int i = 1; ; i++) {
            job.setAttempt(i);
            if (tracker != null) {
                tracker.transportAttempt(i);
            }
            try {
                R value = attempt.run();
                AiUsageSummary summary = recordUsage(job, selection, usage);
                if (selection.reservation() != null) {
                    creditService.consume(job.getUserId(), selection.reservation(), job.getId(), summary);
                }
                job.setStatus(AiRequestStatus.COMPLETED);
                markFinished(job);
                jobRepository.save(job);
                logOutcome("ai_request_completed", job, selection, summary, null);
                analytics.complete(job.getId(), AnalyticsStatus.SUCCEEDED, null, i,
                        tracker == null ? null : tracker.workStartedAt());
                return AiRequestOutcome.fresh(job.getId(), value, selection, null);
            } catch (TimeoutException te) {
                lastError = te;
            } catch (Exception e) {
                lastError = e;
            }

            long backoffMs;
            if (lastError instanceof AiRateLimitedException throttled && rateLimitRetriesLeft > 0) {
                // Throttling has its own, longer budget: a per-minute limit clears in seconds, not 500ms.
                rateLimitRetriesLeft--;
                backoffMs = rateLimitBackoffMs(throttled, rateLimitRetries++);
            } else if (isRetryable(lastError) && !(lastError instanceof AiRateLimitedException) && transientRetriesLeft > 0) {
                transientRetriesLeft--;
                backoffMs = requestProperties.getRetryBackoffMs();
            } else {
                break;
            }
            job.setMaxAttempts(Math.max(job.getMaxAttempts(), i + 1));
            log.warn("event=ai_request_retrying aiRequestId={} attempt={} maxAttempts={} backoffMs={} errorType={} error={}",
                    job.getId(), i, job.getMaxAttempts(), backoffMs, lastError.getClass().getSimpleName(), lastError.getMessage());
            sleeper.sleep(backoffMs);
        }

        AiUsageSummary summary = recordUsage(job, selection, usage);
        if (selection.reservation() != null) {
            creditService.release(job.getUserId(), selection.reservation(), job.getId(), summary);
        }
        job.setStatus(AiRequestStatus.FAILED);
        job.setErrorMessage(lastError != null ? lastError.getMessage() : "Unknown failure");
        markFinished(job);
        jobRepository.save(job);
        // WARN, no stack: the exception is rethrown and logged once by whichever boundary handles it.
        logOutcome("ai_request_failed", job, selection, summary, lastError);
        analytics.fail(job.getId(), lastError, job.getAttempt(), tracker == null ? null : tracker.workStartedAt());

        if (lastError instanceof RuntimeException re) {
            throw re;
        }
        throw new AiRequestTimeoutException(job.getId());
    }

    private AiUsageSummary recordUsage(AiRequestJob job, ModelSelectionOutcome selection, AiUsage usage) {
        AiUsageSummary summary = usage.summarize(selection.model());
        job.setUsage(summary);
        return summary;
    }

    private static void markFinished(AiRequestJob job) {
        Instant now = Instant.now();
        job.setCompletedAt(now);
        if (job.getStartedAt() != null) {
            job.setDurationMs(now.toEpochMilli() - job.getStartedAt().toEpochMilli());
        }
    }

    /**
     * The one INFO/WARN line per AI request (across all its attempts and provider calls): what it was for, which
     * model served it, how long it took, the tokens it used and what it cost. The same figures are persisted on
     * the {@code ai_request_jobs} row, and attributed to the surrounding operation via {@link AiCostLedger}.
     * Cost is tokens x the model's configured prices ({@code ai.models[n].*-usd-per-million}); for a model priced per
     * image or per audio second instead, the rate card's flat price. {@code unpriced} means it is unknown.
     */
    private void logOutcome(String event, AiRequestJob job, ModelSelectionOutcome selection,
                            AiUsageSummary summary, Exception error) {
        if (summary.estimatedCostUsd() == null) {
            Double flat = analytics == null ? null : analytics.flatCostUsd(selection.model().getKey(), job.getCapability(), error == null);
            if (flat != null) {
                summary = new AiUsageSummary(summary.providerCalls(), summary.promptTokens(), summary.cachedTokens(),
                        summary.completionTokens(), summary.thoughtsTokens(), summary.totalTokens(), flat);
            }
        }
        AiCostLedger.record(job.getOperationId(), summary);
        Double costUsd = summary.estimatedCostUsd();
        String costUsdText = costUsd == null ? "unpriced" : String.format("%.6f", costUsd);
        String costInrText = costUsd == null ? "unpriced" : String.format("%.4f", costUsd * requestProperties.getUsdToInr());
        String format = "event={} operation={} capability={} modelKey={} model={} tier={} usedFallback={} "
                + "durationMs={} attempts={} providerCalls={} inputTokens={} cachedTokens={} outputTokens={} "
                + "thoughtsTokens={} costUsd={} costInr={} aiRequestId={}";
        Object[] args = {event, job.getCorrelationType(), job.getCapability(), selection.model().getKey(),
                selection.model().getProviderModelId(), selection.model().getTier(), selection.usedFallback(),
                job.getDurationMs(), job.getAttempt(), summary.providerCalls(), summary.promptTokens(),
                summary.cachedTokens(), summary.completionTokens(), summary.thoughtsTokens(), costUsdText,
                costInrText, job.getId()};
        if (error == null) {
            log.info(format, args);
        } else {
            // No stack: the exception is rethrown and logged once by whichever operation handles it.
            log.warn(format + " errorType={} error=\"{}\"", append(args, error.getClass().getSimpleName(),
                    FailureLogger.oneLine(error.getMessage())));
        }
    }

    private static Object[] append(Object[] args, Object... more) {
        Object[] all = java.util.Arrays.copyOf(args, args.length + more.length);
        System.arraycopy(more, 0, all, args.length, more.length);
        return all;
    }

    private boolean isRetryable(Throwable error) {
        return error instanceof AiTimeoutException || error instanceof AiCommunicationException;
    }

    /**
     * The wait before retry {@code retryIndex} (0-based) of a throttled request: the provider's
     * Retry-After when it gave one, else exponential from {@code rateLimitBackoffMs}; either way
     * capped and spread by up to +20% jitter so parallel workers don't hit the limit again together.
     */
    long rateLimitBackoffMs(AiRateLimitedException throttled, int retryIndex) {
        long cap = requestProperties.getRateLimitMaxBackoffMs();
        long base = throttled.getRetryAfter() != null
                ? throttled.getRetryAfter().toMillis()
                : requestProperties.getRateLimitBackoffMs() << Math.min(retryIndex, 16);
        long bounded = Math.min(Math.max(base, 0L), cap);
        long jitter = bounded <= 0 ? 0 : ThreadLocalRandom.current().nextLong(bounded / 5 + 1);
        return Math.min(bounded + jitter, cap);
    }

    /** Swapped in tests so retry backoff does not really sleep. */
    void setSleeper(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface Attempt<R> {
        R run() throws Exception;
    }
}
