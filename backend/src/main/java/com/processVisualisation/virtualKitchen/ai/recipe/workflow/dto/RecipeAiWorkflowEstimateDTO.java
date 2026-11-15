package com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto;

import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Approximate AI credit usage for a task selection: a min–max range per task and in total, priced
 * with the configured default model of each capability. It is never a quote — the real cost
 * depends on the final number of steps, validation retries and model fallbacks.
 */
@Data
@Builder
public class RecipeAiWorkflowEstimateDTO {

    private List<TaskEstimate> tasks;
    private int minCredits;
    private int maxCredits;
    /** Always true; kept explicit so the UI never presents the figure as exact. */
    private boolean approximate;
    /** False while the step count is an assumed range (the process hasn't been generated yet). */
    private boolean stepCountKnown;

    /** Saved steps that already have an image: what "keep or replace" applies to (0 for a new process). */
    private int existingImages;

    /** Saved steps that already have ready narration (0 for a new process). */
    private int existingNarrations;

    @Data
    @Builder
    public static class TaskEstimate {
        private RecipeAiTaskType task;
        private int minCredits;
        private int maxCredits;
        /** How the range was worked out, e.g. "6–20 steps × (1 prompt + 5 image) credits". */
        private String basis;
    }
}
