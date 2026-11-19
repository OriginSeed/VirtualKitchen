package com.processVisualisation.virtualKitchen.ai.recipe.storyboard.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** A "fix this step's image" request: its status and, once done, the step's new prompt and image. */
@Data
@Builder
public class StoryboardStepFixResponseDTO {
    private String fixId;
    private Long recipeId;
    private Long processId;
    private String stepId;
    /** QUEUED, IN_PROGRESS, COMPLETED or FAILED. */
    private String status;
    private String feedback;
    /** True when a fix was already running for the step and that one is returned. */
    private boolean reused;
    private String imagePrompt;
    private String imageUrl;
    private Long visualizationAssetId;
    private String previousImageUrl;
    private String previousPrompt;
    /** True when the step's current image was sent as the reference to redraw. */
    private boolean usedReference;
    /** True when the feedback was appended to the old prompt because the text model gave no usable one. */
    private boolean promptFromTemplate;
    private String errorMessage;
    private boolean reverted;
    private Instant createdAt;
    private Instant completedAt;
}
