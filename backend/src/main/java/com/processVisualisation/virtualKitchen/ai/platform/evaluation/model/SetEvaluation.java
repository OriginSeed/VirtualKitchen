package com.processVisualisation.virtualKitchen.ai.platform.evaluation.model;

import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationRubric.ReasonTag;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An admin's verdict on one recipe's image set, plus the set scores computed from its {@link ImageEvaluation}s.
 * The id is the set key ({@code recipeId::workflowId}, see {@code EvaluationSets}). Exists as soon as either a
 * verdict or an image score is saved; {@link #verdict} stays null until the admin gives one.
 */
@Data
@Document(collection = "ai_eval_set_scores")
public class SetEvaluation {

    @Id
    private String id;

    /** Compare-and-set counter; incremented by every write, including score recomputation. */
    private long revision;

    private String recipeId;
    @Indexed
    private String modelKey;

    private Verdict verdict;
    private List<ReasonTag> reasonTags = new ArrayList<>();
    private String note;

    private Double meanScore;
    private Double worstScore;
    private Double recipeScore;
    private boolean hardFail;
    private boolean accepted;
    private int scoredImages;
    private int totalImages;
    private String rubricVersion;

    private Long evaluatedBy;
    @Indexed
    private Instant evaluatedAt;
    private Long updatedBy;
    private Instant updatedAt;
}
