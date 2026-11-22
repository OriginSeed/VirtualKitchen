package com.processVisualisation.virtualKitchen.ai.platform.queue;

import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelTier;
import com.processVisualisation.virtualKitchen.ai.platform.usage.AiUsageSummary;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Tracks a single AI provider call end-to-end: which model was requested vs.
 * actually used, whether fallback kicked in, the credit reservation it holds,
 * and its {@link AiRequestStatus}. A finer-grained sibling of {@code
 * VisualizationJob} (which tracks a whole multi-step visualization batch) —
 * one {@code AiRequestJob} backs each individual provider call, including the
 * ones inside such a batch.
 * <p>
 * The compound unique index on {@code (userId, idempotencyKey)} is the
 * request-level duplicate-submission guard: a second concurrent submission
 * with the same key fails with a Mongo {@code DuplicateKeyException} (handled
 * generically by {@code GlobalExceptionHandler} as HTTP 409) before any model
 * selection or credit reservation happens for it.
 */
@Data
@Document(collection = "ai_request_jobs")
// The stale-job reconciler looks up stuck requests by status and start / queue time.
@CompoundIndex(name = "status_started_idx", def = "{'status': 1, 'startedAt': 1}")
@CompoundIndex(name = "status_queued_idx", def = "{'status': 1, 'queuedAt': 1}")
@CompoundIndex(name = "user_idempotency_idx", def = "{'userId': 1, 'idempotencyKey': 1}", unique = true)
public class AiRequestJob {

    @Id
    private String id;

    @Indexed
    private Long userId;

    private AiCapability capability;

    private String requestedModelKey;
    private String resolvedModelKey;
    private ModelTier resolvedTier;
    private boolean usedFallback;
    private String fallbackReason;

    private int creditCost;
    private String creditTransactionId;

    private AiRequestStatus status;

    private String idempotencyKey;

    private int attempt;
    private int maxAttempts;

    /** e.g. {@code "recipe-generation"}, {@code "visualization-step"}, {@code "chat"}. */
    private String correlationType;

    /** e.g. a recipeId, or {@code "<recipeId>:<stepId>"}. */
    private String correlationId;

    /**
     * The background operation this request ran for (the {@code jobId} MDC key: a visualization or generation
     * job id, or {@code narration:<recipeId>::<stepId>}); null for requests made directly from an HTTP call.
     */
    @Indexed(sparse = true)
    private String operationId;

    private Instant queuedAt;
    private Instant startedAt;
    private Instant completedAt;
    /** From model selection to the final outcome, across every attempt and retry back-off. */
    private Long durationMs;

    private String errorMessage;

    /** Token usage and estimated cost over all provider calls of this job — recorded for every tier, free or paid. */
    private AiUsageSummary usage;
}
