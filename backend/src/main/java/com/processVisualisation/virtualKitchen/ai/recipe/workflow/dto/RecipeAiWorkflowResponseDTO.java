package com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto;

import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.RecipeProcessGenerationJobResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.dto.VisualizationJobResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiWorkflowStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Current state of an AI Recipe Creation workflow, with every task's status and progress derived
 * from the jobs doing the work.
 */
@Data
@Builder
public class RecipeAiWorkflowResponseDTO {

    private String workflowId;
    private Long recipeId;
    private RecipeAiWorkflowStatus status;
    private List<RecipeAiTaskType> selectedTasks;
    /** One entry per {@link RecipeAiTaskType}, in order; unselected tasks are SKIPPED. */
    private List<TaskDTO> tasks;
    /** Σ(weight × task fraction) / Σ weight over selected, non-skipped tasks; 0–100. */
    private int progressPercent;
    /**
     * True only while discarding really stops everything — before approval, with no generation
     * running. Running AI calls can't be interrupted, so the UI must not offer to cancel them.
     */
    private boolean cancellable;
    private String generationJobId;
    /** True once the generated process was loaded into the editor (the approval button needs this). */
    private boolean generationApplied;
    /**
     * The generation job, included only on the create/retry responses so the editor can start
     * tracking it straight away (it is then polled through its own endpoint, as before).
     */
    private RecipeProcessGenerationJobResponseDTO generation;
    /** Latest visualization job per approved process, so the editor can show images as they arrive. */
    private List<VisualizationJobResponseDTO> visualizationJobs;
    private Long approvedRevision;
    private Instant approvedAt;
    private Instant createdAt;
    private Instant completedAt;
    /** True when create joined the recipe's already-open workflow instead of starting another. */
    private boolean reused;
    /**
     * The models this run uses (capability name to model key) where it overrides the user's account setting; what
     * the approval and retry pickers start from. Empty: the account setting for everything.
     */
    private Map<String, String> modelKeys;

    /** Whether the run regenerates and replaces images and narration the steps already have. */
    private boolean overwriteExisting;

    @Data
    @Builder
    public static class TaskDTO {
        private RecipeAiTaskType type;
        private RecipeAiTaskStatus status;
        private int weight;
        /** 0–100: generation stage for PROCESS, finished items / total for VISUALS and NARRATION. */
        private int progressPercent;
        /** Items finished successfully (steps with an image / with narration). */
        private int completed;
        private int failed;
        /** Items the task covers; 0 for PROCESS and before approval. */
        private int total;
        /** PROCESS only: the generation job's stage (QUEUED, CALLING_MODEL, …). */
        private String stage;
        private String errorMessage;
        private boolean retryable;
        /** NARRATION: failed steps can't be retried before this instant (the service's back-off). */
        private Instant retryAfter;
    }
}
