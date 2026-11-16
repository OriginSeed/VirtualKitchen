package com.processVisualisation.virtualKitchen.ai.recipe.storyboard.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/** One storyboard generation: its progress while it runs, and its panels and image once it is done. */
@Data
@Builder
public class RecipeStoryboardResponseDTO {

    private String jobId;
    private Long recipeId;
    /** QUEUED, IN_PROGRESS, COMPLETED or FAILED. */
    private String status;
    /** PLAN, PROMPT, IMAGE or CROP: what is being worked on. */
    private String stage;
    private String failedStage;
    private String errorMessage;
    private List<String> warnings;
    /** True when the start request joined a generation that was already running. */
    private boolean reused;
    /** True when the recipe changed after this storyboard was made. */
    private boolean outdated;

    private String title;
    private String imageUrl;
    private Integer rows;
    private Integer columns;
    private List<PanelDTO> panels;
    private List<OmittedStepDTO> omittedSteps;
    /** The prompt the image was requested with; only sent to the recipe's owner. */
    private String masterPrompt;
    /** What happened to each covered step when the panel images were put on the steps. */
    private List<StepDTO> steps;

    private String textModelKey;
    private String imageModelKey;
    private Instant createdAt;
    private Instant completedAt;

    @Data
    @Builder
    public static class PanelDTO {
        private String panelId;
        private int sequence;
        private String group;
        private String component;
        private String description;
        private List<String> sourceStepIds;
        private String imagePrompt;
        private List<String> dependsOn;
        private List<String> referencePanels;
        private List<String> ingredients;
        private List<String> equipment;
        private String expectedState;
        private String imageUrl;
    }

    @Data
    @Builder
    public static class OmittedStepDTO {
        private String stepId;
        private String reason;
    }

    @Data
    @Builder
    public static class StepDTO {
        private Long processId;
        private String stepId;
        private String panelId;
        private Long visualizationAssetId;
        private String imageUrl;
        private String imagePrompt;
        private boolean attached;
    }
}
