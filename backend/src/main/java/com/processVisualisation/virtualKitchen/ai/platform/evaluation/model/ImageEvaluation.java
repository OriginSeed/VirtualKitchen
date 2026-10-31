package com.processVisualisation.virtualKitchen.ai.platform.evaluation.model;

import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationRubric.DefectTag;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An admin's quality scores for one generated step image. The id is the {@code ai_request_analytics} id of the
 * request that produced the image, so captured metrics (cost, time, model) stay in analytics and are joined, never
 * copied. Ratings are 1–5 and nullable: null means "not rated", never zero.
 */
@Data
@Document(collection = "ai_eval_image_scores")
public class ImageEvaluation {

    /** How many earlier versions {@link #edits} keeps. */
    public static final int MAX_EDITS = 10;

    @Id
    private String id;

    /** Compare-and-set counter; incremented by every write. */
    private long revision;

    @Indexed
    private String setKey;
    private String recipeId;
    private String stepId;
    @Indexed
    private String modelKey;
    private String provider;
    private String tier;

    private Integer consistency;
    private Integer stepCorrectness;
    private Integer stateContinuity;
    private Integer realism;
    private List<DefectTag> defectTags = new ArrayList<>();
    private String note;

    /** 0–100 under {@link #rubricVersion}; null when nothing was rated. */
    private Double imageScore;
    private String rubricVersion;

    /** Reserved for an automatic judge's scores; empty until one exists. */
    private Map<String, Object> autoScores = new LinkedHashMap<>();

    private Long evaluatedBy;
    @Indexed
    private Instant evaluatedAt;
    private Long updatedBy;
    private Instant updatedAt;

    /** Earlier values, newest last, kept when the scores are edited. */
    private List<Edit> edits = new ArrayList<>();

    @Data
    public static class Edit {
        private Integer consistency;
        private Integer stepCorrectness;
        private Integer stateContinuity;
        private Integer realism;
        private List<DefectTag> defectTags;
        private String note;
        private Double imageScore;
        private Long by;
        private Instant at;
    }
}
