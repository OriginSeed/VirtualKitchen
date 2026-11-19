package com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model;

import org.springframework.data.mongodb.core.index.CompoundIndex;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One "fix this step's image" request: the cook's feedback, the image prompt rewritten from it, and the image drawn
 * again against the step's current picture. The document is both the job (polled by the client) and its record: it
 * keeps the step's previous prompt and image so the fix can be reverted.
 */
@Data
@Document(collection = "storyboard_step_fix")
// The stale-job reconciler looks up active jobs by status and last write every few minutes.
@CompoundIndex(name = "status_updated_idx", def = "{'status': 1, 'updatedAt': 1}")
public class StoryboardStepFix {

    @Id
    private String id;

    @Indexed
    private Long recipeId;

    private Long processId;

    private String stepId;

    @Indexed
    private Long userId;

    private StoryboardStatus status;

    /** What the cook said is wrong with the image. */
    private String feedback;

    /** {@code fix:<recipeId>:<stepId>} while running: one fix per step at a time. */
    @Indexed(unique = true, sparse = true)
    private String activeKey;

    private String previousPrompt;

    private String previousImageUrl;

    private String previousSourceGridKey;

    private String newPrompt;

    private String newImageUrl;

    /** True when the step's current image (or its panel's) was sent along as the reference to redraw. */
    private boolean usedReference;

    /** True when the text model gave no usable prompt and the feedback was appended to the old one instead. */
    private boolean promptFromTemplate;

    private Long visualizationAssetId;

    private String imageModelKey;

    private String errorMessage;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant completedAt;

    /** Set when the step was put back to its previous prompt and image. */
    private Instant revertedAt;
}
