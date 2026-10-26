package com.processVisualisation.virtualKitchen.ai.platform.analytics.model;

/** What the application used an AI request for. Mapped from the queue's correlation type. */
public enum OperationContext {
    RECIPE_FLOW_GENERATION,
    RECIPE_FLOW_EDIT,
    IMAGE_PROMPT_GENERATION,
    STEP_IMAGE_GENERATION,
    STEP_NARRATION,
    GLOBAL_ASSET_IMAGE,
    CHAT,
    UNKNOWN;

    /** Maps a queue correlation type ({@code AiRequestJob.correlationType}) to its context. */
    public static OperationContext fromCorrelationType(String correlationType) {
        if (correlationType == null) {
            return UNKNOWN;
        }
        return switch (correlationType) {
            case "process-generation" -> RECIPE_FLOW_GENERATION;
            case "process-edit" -> RECIPE_FLOW_EDIT;
            case "process-visualization-prompt", "process-visualization-prompt-batch" -> IMAGE_PROMPT_GENERATION;
            case "process-visualization-image" -> STEP_IMAGE_GENERATION;
            case "step-narration" -> STEP_NARRATION;
            case "global-asset-image" -> GLOBAL_ASSET_IMAGE;
            case "chat" -> CHAT;
            default -> UNKNOWN;
        };
    }
}
