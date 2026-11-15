package com.processVisualisation.virtualKitchen.ai.recipe.visualization.model;

import com.processVisualisation.virtualKitchen.ai.platform.usage.AiCostLedger;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks the progress of an async recipe-visualization run started via
 * {@code RecipeProcessVisualizationJobService.startJob}. {@code completedSteps} and
 * {@code stepResults} are updated atomically (Mongo {@code $inc}/{@code $push})
 * as steps complete concurrently, never via read-modify-write, since several
 * worker threads can finish steps for the same job at the same time.
 */
@Data
@Document(collection = "visualization_job")
public class VisualizationJob {

    @Id
    private String id;

    @Indexed
    private String recipeId;

    /** The MAIN or SUBPROCESS whose own steps this job visualizes (null only on jobs recorded before the Process model). */
    private Long processId;

    @Indexed
    private Long userId;

    private VisualizationJobStatus status;

    private int totalSteps;

    /** Started with "regenerate and replace": the steps' existing images were cleared and drawn again. */
    private boolean overwriteExisting;

    private int completedSteps;

    private List<StepResult> stepResults = new ArrayList<>();

    private String errorMessage;

    /**
     * Set to {@code viz:<processId>} only while the job is QUEUED/IN_PROGRESS and unset once it
     * terminates. The unique sparse index makes "one running visualization per process" atomic:
     * a concurrent second start fails the insert and joins the job that won instead.
     */
    @Indexed(unique = true, sparse = true)
    private String activeKey;

    private Instant createdAt;

    private Instant startedAt;

    private Instant completedAt;

    private Instant updatedAt;

    /**
     * Outcome of a single step's visualization generation within a
     * {@link VisualizationJob}, including the produced asset/image on
     * success or an error message on failure.
     */
    @Data
    public static class StepResult {
        private String stepId;
        private boolean success;
        private Long visualizationAssetId;
        private String imageUrl;
        private String errorMessage;
        private String modelKey;
        private String tier;
        private boolean usedFallback;
    }

    /** Total AI requests, tokens and estimated cost of this job, recorded when it finishes. */
    private AiCostLedger.Totals aiUsage;
}
