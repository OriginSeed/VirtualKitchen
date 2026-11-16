package com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model;

import com.processVisualisation.virtualKitchen.ai.platform.usage.AiCostLedger;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One generation of a recipe's storyboard: the panels an LLM planned from the whole recipe flow, the master image
 * prompt written from them, the one combined image drawn from that prompt, and the panel images cut from it. The
 * document is both the job (status, stage, polled by the client) and its result; earlier generations are kept, and
 * the recipe's storyboard is the latest COMPLETED one.
 */
@Data
@Document(collection = "recipe_storyboard")
public class RecipeStoryboard {

    @Id
    private String id;

    @Indexed
    private Long recipeId;

    @Indexed
    private Long userId;

    private StoryboardStatus status;

    /** The stage being worked on (or last worked on). */
    private StoryboardStage stage;

    /** The stage a FAILED generation stopped at. */
    private StoryboardStage failedStage;

    private String errorMessage;

    /** Problems that did not stop the generation (a dropped dependency, panels that could not be cut). */
    private List<String> warnings = new ArrayList<>();

    /**
     * {@code storyboard:<recipeId>} while QUEUED/IN_PROGRESS, unset once it ends: the unique sparse index allows one
     * running storyboard per recipe.
     */
    @Indexed(unique = true, sparse = true)
    private String activeKey;

    /** Replace images the covered steps already have (otherwise only steps without an image get a panel image). */
    private boolean overwriteExisting;

    /** The recipe's process revision when this was generated. */
    private Long processRevision;

    /** Hash of the recipe flow the panels were planned from; a different current hash means the storyboard is outdated. */
    private String sourceHash;

    private String title;

    private String dishContext;

    private List<Panel> panels = new ArrayList<>();

    private List<OmittedStep> omittedSteps = new ArrayList<>();

    private Layout layout;

    /** The one prompt the combined image was requested with. */
    private String masterPrompt;

    /** The combined storyboard image. */
    private String imageUrl;

    private Integer imageWidth;

    private Integer imageHeight;

    private String textModelKey;

    private String imageModelKey;

    private String imageModelTier;

    private boolean usedFallback;

    /** What happened to each covered step when the panel images were attached. */
    private List<StepResult> stepResults = new ArrayList<>();

    private Instant createdAt;

    private Instant startedAt;

    private Instant updatedAt;

    private Instant completedAt;

    private AiCostLedger.Totals aiUsage;

    /** One panel: a visually meaningful cooking action or preparation outcome, and the recipe nodes it stands for. */
    @Data
    public static class Panel {
        private String panelId;
        private int sequence;
        /** PREPARATION or MAIN. */
        private String group;
        /** The prepared component a preparation panel works towards, when it has one. */
        private String component;
        private String description;
        /** Node ids (of the recipe's processes) this panel represents. */
        private List<String> sourceStepIds = new ArrayList<>();
        private String imagePrompt;
        /** Panels that must logically happen first. */
        private List<String> dependsOn = new ArrayList<>();
        /** Earlier panels whose look reappears here (a prepared component being used). */
        private List<String> referencePanels = new ArrayList<>();
        private List<String> ingredients = new ArrayList<>();
        private List<String> equipment = new ArrayList<>();
        private String expectedState;
        /** This panel's image, cut from the combined image; null when it could not be cut. */
        private String imageUrl;
        private Crop crop;
    }

    /** Where a panel sits in the combined image, in pixels. */
    @Data
    public static class Crop {
        private int x;
        private int y;
        private int width;
        private int height;
    }

    /** A recipe step that got no panel of its own, and why. */
    @Data
    public static class OmittedStep {
        private String stepId;
        private String reason;
    }

    /** The grid of the combined image; panels are read left to right, top to bottom. */
    @Data
    public static class Layout {
        private int rows;
        private int columns;
    }

    /** One covered step: the panel it belongs to and whether the panel's image was put on the step. */
    @Data
    public static class StepResult {
        private Long processId;
        private String stepId;
        private String panelId;
        private Long visualizationAssetId;
        private String imageUrl;
        private String imagePrompt;
        /** False when the step kept the image it already had. */
        private boolean attached;
    }
}
