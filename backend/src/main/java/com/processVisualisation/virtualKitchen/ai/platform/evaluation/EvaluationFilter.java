package com.processVisualisation.virtualKitchen.ai.platform.evaluation;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;
import java.util.List;

/** Filters for image sets. Date range and recipe go to MongoDB; model, tier and status apply per set. */
@Data
public class EvaluationFilter {

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant from;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant to;
    private String recipeId;
    /** The set's (majority) model. */
    private List<String> model;
    private List<String> tier;
    /** UNSCORED, PARTIAL or SCORED. */
    private List<String> status;
    /** Include sets whose images came from more than one model (fallback). */
    private boolean includeMixed;
}
