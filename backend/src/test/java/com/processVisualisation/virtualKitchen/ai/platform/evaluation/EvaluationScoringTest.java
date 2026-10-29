package com.processVisualisation.virtualKitchen.ai.platform.evaluation;

import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationRubric.DefectTag;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationScoring.SetScores;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.ImageEvaluation;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.Verdict;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EvaluationScoringTest {

    @Test
    void defectRating_noTagsIsFive_oneMinorIsThree_majorOrSeveralIsOne() {
        assertThat(EvaluationScoring.defectRating(List.of())).isEqualTo(5);
        assertThat(EvaluationScoring.defectRating(null)).isEqualTo(5);
        assertThat(EvaluationScoring.defectRating(List.of(DefectTag.ODD_CROP))).isEqualTo(3);
        assertThat(EvaluationScoring.defectRating(List.of(DefectTag.WRONG_COOKWARE))).isEqualTo(1);
        assertThat(EvaluationScoring.defectRating(List.of(DefectTag.ODD_CROP, DefectTag.LIGHTING_SHIFT))).isEqualTo(1);
    }

    @Test
    void imageScore_allTopRatingsWithoutDefectsIsHundred_allBottomWithMajorDefectIsZero() {
        assertThat(EvaluationScoring.imageScore(5, 5, 5, 5, List.of())).isEqualTo(100.0);
        assertThat(EvaluationScoring.imageScore(1, 1, 1, 1, List.of(DefectTag.MELTED_OBJECTS))).isEqualTo(0.0);
    }

    @Test
    void imageScore_weightsDimensions() {
        // consistency 3 (50) ×35, step 5 (100) ×30, continuity 1 (0) ×15, defects 5 (100) ×15, realism 5 (100) ×5
        double expected = (50 * 35 + 100 * 30 + 0 * 15 + 100 * 15 + 100 * 5) / 100.0;
        assertThat(EvaluationScoring.imageScore(3, 5, 1, 5, List.of())).isCloseTo(expected, within(1e-9));
    }

    @Test
    void imageScore_skipsUnratedDimensions_andIsNullWhenNothingIsRated() {
        // Only step correctness 5 (×30) and the derived defects 3 (×15) count.
        double expected = (100 * 30 + 50 * 15) / 45.0;
        assertThat(EvaluationScoring.imageScore(null, 5, null, null, List.of(DefectTag.ODD_CROP)))
                .isCloseTo(expected, within(1e-9));
        assertThat(EvaluationScoring.imageScore(null, null, null, null, List.of(DefectTag.ODD_CROP))).isNull();
    }

    @Test
    void setScores_blendMeanAndWorst() {
        SetScores scores = EvaluationScoring.setScores(List.of(image(80.0, 4), image(40.0, 3), image(null, null)),
                Verdict.ACCEPTED);

        assertThat(scores.scoredImages()).isEqualTo(2);
        assertThat(scores.meanScore()).isEqualTo(60.0);
        assertThat(scores.worstScore()).isEqualTo(40.0);
        assertThat(scores.recipeScore()).isCloseTo(0.7 * 60 + 0.3 * 40, within(1e-9));
        assertThat(scores.hardFail()).isFalse();
        assertThat(scores.accepted()).isTrue();
    }

    @Test
    void setScores_lowStepCorrectnessIsAHardFail_soAnAcceptedVerdictDoesNotCountAsAccepted() {
        SetScores scores = EvaluationScoring.setScores(List.of(image(90.0, 5), image(70.0, 2)), Verdict.ACCEPTED);

        assertThat(scores.hardFail()).isTrue();
        assertThat(scores.accepted()).isFalse();
    }

    @Test
    void setScores_rejectedIsAHardFail_andNoScoresMeansNoRecipeScore() {
        SetScores rejected = EvaluationScoring.setScores(List.of(), Verdict.REJECTED);
        assertThat(rejected.hardFail()).isTrue();
        assertThat(rejected.recipeScore()).isNull();

        SetScores noVerdict = EvaluationScoring.setScores(List.of(image(90.0, 5)), null);
        assertThat(noVerdict.hardFail()).isFalse();
        assertThat(noVerdict.accepted()).isFalse();
    }

    @Test
    void rubricView_weightsAddUpToHundred() {
        int total = Arrays.stream(EvaluationRubric.Dimension.values()).mapToInt(EvaluationRubric.Dimension::weight).sum();
        assertThat(total).isEqualTo(100);
        assertThat(EvaluationRubric.view().defectTags()).hasSize(DefectTag.values().length);
    }

    static ImageEvaluation image(Double score, Integer stepCorrectness) {
        ImageEvaluation evaluation = new ImageEvaluation();
        evaluation.setImageScore(score);
        evaluation.setStepCorrectness(stepCorrectness);
        return evaluation;
    }
}
