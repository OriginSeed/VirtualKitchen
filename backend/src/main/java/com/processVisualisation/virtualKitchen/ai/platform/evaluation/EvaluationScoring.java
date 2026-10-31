package com.processVisualisation.virtualKitchen.ai.platform.evaluation;

import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationRubric.DefectTag;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationRubric.Dimension;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.ImageEvaluation;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.Verdict;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Score definitions for {@link EvaluationRubric} v1. Pure, so every rule is unit-tested.
 * <ul>
 *     <li>A 1–5 rating maps linearly to 0–100.</li>
 *     <li><b>Image score</b>: weighted mean of the dimensions that were rated, plus {@code DEFECTS} (from the tags).
 *     An image with no rating at all is unscored (null), not zero.</li>
 *     <li><b>Defects rating</b>: no tags 5; one minor tag 3; a major tag or two or more tags 1.</li>
 *     <li><b>Recipe score</b>: {@code 0.7 × mean + 0.3 × worst} over the scored images.</li>
 *     <li><b>Hard fail</b>: any image with step correctness ≤ 2, or the verdict is REJECTED.</li>
 *     <li><b>Accepted</b>: the verdict is ACCEPTED and the set is not a hard fail.</li>
 * </ul>
 */
public final class EvaluationScoring {

    private EvaluationScoring() {
    }

    public static int defectRating(Collection<DefectTag> tags) {
        if (tags == null || tags.isEmpty()) return 5;
        if (tags.size() >= 2 || tags.stream().anyMatch(DefectTag::major)) return 1;
        return 3;
    }

    /** Null when none of the rated dimensions has a value. */
    public static Double imageScore(Integer consistency, Integer stepCorrectness, Integer stateContinuity,
                                    Integer realism, Collection<DefectTag> defectTags) {
        if (consistency == null && stepCorrectness == null && stateContinuity == null && realism == null) {
            return null;
        }
        double weighted = 0;
        int weights = 0;
        Integer[] ratings = {consistency, stepCorrectness, stateContinuity, realism, defectRating(defectTags)};
        Dimension[] dims = {Dimension.CONSISTENCY, Dimension.STEP_CORRECTNESS, Dimension.STATE_CONTINUITY,
                Dimension.REALISM, Dimension.DEFECTS};
        for (int i = 0; i < ratings.length; i++) {
            if (ratings[i] == null) continue;
            weighted += toPercent(ratings[i]) * dims[i].weight();
            weights += dims[i].weight();
        }
        return weighted / weights;
    }

    public static double toPercent(int rating) {
        return (rating - 1) / 4.0 * 100;
    }

    public record SetScores(Double meanScore, Double worstScore, Double recipeScore, int scoredImages,
                            boolean hardFail, boolean accepted) {
    }

    public static SetScores setScores(List<ImageEvaluation> images, Verdict verdict) {
        List<Double> scores = images.stream().map(ImageEvaluation::getImageScore).filter(Objects::nonNull).toList();
        boolean stepFailed = images.stream().anyMatch(i -> i.getStepCorrectness() != null
                && i.getStepCorrectness() <= EvaluationRubric.HARD_FAIL_STEP_CORRECTNESS);
        boolean hardFail = stepFailed || verdict == Verdict.REJECTED;
        if (scores.isEmpty()) {
            return new SetScores(null, null, null, 0, hardFail, verdict == Verdict.ACCEPTED && !hardFail);
        }
        double mean = scores.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double worst = scores.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double recipe = EvaluationRubric.MEAN_WEIGHT * mean + EvaluationRubric.WORST_WEIGHT * worst;
        return new SetScores(mean, worst, recipe, scores.size(), hardFail, verdict == Verdict.ACCEPTED && !hardFail);
    }
}
