package com.processVisualisation.virtualKitchen.ai.recipe.generation.model;

import org.springframework.data.mongodb.core.index.CompoundIndex;
import com.processVisualisation.virtualKitchen.ai.platform.usage.AiCostLedger;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.ProcessGenerationMode;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.RecipeProcessGenerationResultDTO;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Tracks the progress of an async AI Process generation run started via
 * {@code RecipeProcessGenerationJobService.startJob}. Its lifecycle is
 * {@link RecipeProcessGenerationJobStatus} (QUEUED/IN_PROGRESS/COMPLETED/FAILED).
 */
@Data
@Document(collection = "process_generation_job")
// The stale-job reconciler looks up active jobs by status and last write every few minutes.
@CompoundIndex(name = "status_updated_idx", def = "{'status': 1, 'updatedAt': 1}")
public class RecipeProcessGenerationJob {

    @Id
    private String id;

    @Indexed
    private Long userId;

    @Indexed
    private Long recipeId;

    @Indexed
    private String clientRequestId;

    private RecipeProcessGenerationJobStatus status;

    private RecipeProcessGenerationStage stage;

    /** CREATE or EDIT; null on jobs stored before modes existed (= CREATE). */
    private ProcessGenerationMode mode;

    private RecipeProcessGenerationResultDTO result;

    private String errorMessage;

    /**
     * Set to {@code gen:<userId>:<recipeId>} only while the job is QUEUED/IN_PROGRESS and unset once
     * it terminates. The unique sparse index makes "one running generation per user+recipe" atomic:
     * a concurrent second start fails the insert and joins the job that won instead.
     */
    @Indexed(unique = true, sparse = true)
    private String activeKey;

    /** When the frontend loaded {@link #result} into the user's working session; null until then. */
    private Instant resultAppliedAt;

    private Instant createdAt;

    private Instant startedAt;

    private Instant completedAt;

    private Instant updatedAt;

    /** Total AI requests, tokens and estimated cost of this job, recorded when it finishes. */
    private AiCostLedger.Totals aiUsage;
}
