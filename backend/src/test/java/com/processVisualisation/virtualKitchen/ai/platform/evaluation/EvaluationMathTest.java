package com.processVisualisation.virtualKitchen.ai.platform.evaluation;

import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AiRequestAnalytics;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AnalyticsStatus;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationMath.ModelComparison;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationRubric.DefectTag;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationSets.ImageSet;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.ImageEvaluation;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.SetEvaluation;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationTestSupport.image;
import static com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationTestSupport.row;
import static com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationTestSupport.scored;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class EvaluationMathTest {

    private static final String GEMINI = "gemini-image";
    private static final String SDXL = "drawthings-sdxl";

    // --- grouping ---

    @Test
    void setKey_usesWorkflow_elseJob_elseRequest() {
        AiRequestAnalytics withWorkflow = row("a", "7", "wf1", "s1", GEMINI, AnalyticsStatus.SUCCEEDED, 0.04, 0, 1000);
        AiRequestAnalytics withJob = row("b", "7", null, "s1", GEMINI, AnalyticsStatus.SUCCEEDED, 0.04, 0, 1000);
        withJob.setJobId("job9");
        AiRequestAnalytics alone = row("c", null, null, "s1", GEMINI, AnalyticsStatus.SUCCEEDED, 0.04, 0, 1000);

        assertThat(EvaluationSets.setKey(withWorkflow)).isEqualTo("7::wf1");
        assertThat(EvaluationSets.setKey(withJob)).isEqualTo("7::job:job9");
        assertThat(EvaluationSets.setKey(alone)).isEqualTo("unknown::req:c");

        assertThat(EvaluationSets.parse("7::wf1")).isEqualTo(new EvaluationSets.KeyParts("7", "wf1", null, null));
        assertThat(EvaluationSets.parse("7::job:job9")).isEqualTo(new EvaluationSets.KeyParts("7", null, "job9", null));
        assertThat(EvaluationSets.parse("unknown::req:c")).isEqualTo(new EvaluationSets.KeyParts(null, null, null, "c"));
        assertThatThrownBy(() -> EvaluationSets.parse("no-separator")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void group_imagesExcludeSceneAndFailures_butCostIncludesThem() {
        List<ImageSet> sets = EvaluationSets.group(List.of(
                row("scene", "7", "wf1", "scene", GEMINI, AnalyticsStatus.SUCCEEDED, 0.04, 0, 2000),
                image("s1", "7", "wf1", "s1", GEMINI, 0.04, 2, 3000),
                row("s2-fail", "7", "wf1", "s2", GEMINI, AnalyticsStatus.FAILED, 0.04, 5, 1000),
                image("s2", "7", "wf1", "s2", GEMINI, 0.04, 6, 4000)));

        assertThat(sets).hasSize(1);
        ImageSet set = sets.get(0);
        assertThat(set.images()).extracting(AiRequestAnalytics::getId).containsExactly("s1", "s2");
        assertThat(set.failedRequests()).isEqualTo(1);
        assertThat(set.spanMs()).isEqualTo(10_000L); // t0 → t0+6s+4s
        EvaluationMath.SetCost cost = EvaluationMath.setCost(set);
        assertThat(cost.total()).isCloseTo(0.16, within(1e-9));
        assertThat(cost.complete()).isTrue();
        assertThat(cost.currency()).isEqualTo("USD");
    }

    @Test
    void group_mixedModelsAttributeToTheMajority_andNewestSetComesFirst() {
        List<ImageSet> sets = EvaluationSets.group(List.of(
                image("a1", "7", "old", "s1", SDXL, 0.0, 0, 1000),
                image("b1", "7", "new", "s1", GEMINI, 0.04, 100, 1000),
                image("b2", "7", "new", "s2", SDXL, 0.0, 101, 1000),
                image("b3", "7", "new", "s3", GEMINI, 0.04, 102, 1000)));

        assertThat(sets).extracting(ImageSet::setKey).containsExactly("7::new", "7::old");
        assertThat(sets.get(0).modelKey()).isEqualTo(GEMINI);
        assertThat(sets.get(0).mixedModels()).isTrue();
        assertThat(sets.get(1).mixedModels()).isFalse();
    }

    @Test
    void setCost_isIncompleteWhenARequestCouldNotBeCosted() {
        ImageSet set = EvaluationSets.group(List.of(
                image("s1", "7", "wf1", "s1", GEMINI, 0.04, 0, 1000),
                row("s2", "7", "wf1", "s2", GEMINI, AnalyticsStatus.SUCCEEDED, null, 1, 1000))).get(0);

        assertThat(EvaluationMath.setCost(set).complete()).isFalse();
    }

    // --- comparison ---

    @Test
    void compare_reportsQualityCostAndTimePerModel() {
        List<ImageSet> sets = EvaluationSets.group(List.of(
                // Gemini: two sets, one accepted, one rejected
                image("g1", "1", "wfA", "s1", GEMINI, 0.04, 0, 1000),
                image("g2", "1", "wfA", "s2", GEMINI, 0.04, 1, 3000),
                image("g3", "2", "wfB", "s1", GEMINI, 0.04, 10, 2000),
                row("g3f", "2", "wfB", "s2", GEMINI, AnalyticsStatus.FAILED, 0.04, 12, 1000),
                image("g4", "2", "wfB", "s2", GEMINI, 0.04, 13, 2000),
                // SDXL: one unscored set
                image("d1", "3", "wfC", "s1", SDXL, 0.002, 20, 9000)));

        Map<String, SetEvaluation> setEvaluations = Map.of(
                "1::wfA", verdict("1::wfA", Verdict.ACCEPTED, 80.0, false),
                "2::wfB", verdict("2::wfB", Verdict.REJECTED, 50.0, true));
        List<ImageEvaluation> images = List.of(
                scored("g1", "1::wfA", GEMINI, 5, List.of()),
                scored("g2", "1::wfA", GEMINI, 4, List.of(DefectTag.ODD_CROP)),
                scored("g3", "2::wfB", GEMINI, 3, List.of(DefectTag.WRONG_COOKWARE)),
                scored("g4", "2::wfB", GEMINI, 2, List.of(DefectTag.WRONG_COOKWARE)));

        List<ModelComparison> models = EvaluationMath.compare(sets, setEvaluations, images, false);

        assertThat(models).extracting(ModelComparison::modelKey).containsExactly(GEMINI, SDXL);
        ModelComparison gemini = models.get(0);
        assertThat(gemini.sets()).isEqualTo(2);
        assertThat(gemini.images()).isEqualTo(4);
        assertThat(gemini.failedRequests()).isEqualTo(1);
        assertThat(gemini.currency()).isEqualTo("USD");
        assertThat(gemini.costPerImage().value()).isCloseTo(0.04, within(1e-9));
        // set A 0.08, set B 0.12 (failed attempt included)
        assertThat(gemini.costPerSet().value()).isCloseTo(0.10, within(1e-9));
        // both sets paid for, one accepted
        assertThat(gemini.costPerAcceptedSet()).isCloseTo(0.20, within(1e-9));
        assertThat(gemini.p95ImageMs().value()).isEqualTo(3000.0);
        assertThat(gemini.scoredSets()).isEqualTo(2);
        assertThat(gemini.scoredImages()).isEqualTo(4);
        assertThat(gemini.recipeScore().value()).isEqualTo(65.0);
        assertThat(gemini.acceptanceRate()).isEqualTo(0.5);
        assertThat(gemini.hardFailRate()).isEqualTo(0.5);
        assertThat(gemini.dimensions().get("CONSISTENCY").value()).isEqualTo(3.5);
        assertThat(gemini.dimensions().get("DEFECTS").value()).isEqualTo((5 + 3 + 1 + 1) / 4.0);
        assertThat(gemini.defectRate()).isEqualTo(0.75);
        assertThat(gemini.topDefects().get(0)).isEqualTo(new EvaluationMath.TagCount("WRONG_COOKWARE", 2));
        assertThat(gemini.qualityPerCost()).isCloseTo(650.0, within(1e-6));
        assertThat(gemini.lowSample()).isTrue();

        ModelComparison sdxl = models.get(1);
        assertThat(sdxl.scoredSets()).isZero();
        assertThat(sdxl.recipeScore().value()).isNull();
        assertThat(sdxl.acceptanceRate()).isNull();
        assertThat(sdxl.costPerAcceptedSet()).isNull();
        assertThat(sdxl.p50SetMs().value()).isEqualTo(9000.0);
    }

    @Test
    void compare_leavesOutMixedModelSetsUnlessAsked() {
        List<ImageSet> sets = EvaluationSets.group(List.of(
                image("b1", "7", "wf", "s1", GEMINI, 0.04, 0, 1000),
                image("b2", "7", "wf", "s2", SDXL, 0.0, 1, 1000)));

        assertThat(EvaluationMath.compare(sets, Map.of(), List.of(), false)).isEmpty();
        assertThat(EvaluationMath.compare(sets, Map.of(), List.of(), true)).hasSize(1);
    }

    private static SetEvaluation verdict(String key, Verdict verdict, double recipeScore, boolean hardFail) {
        SetEvaluation evaluation = new SetEvaluation();
        evaluation.setId(key);
        evaluation.setVerdict(verdict);
        evaluation.setRecipeScore(recipeScore);
        evaluation.setHardFail(hardFail);
        evaluation.setAccepted(verdict == Verdict.ACCEPTED && !hardFail);
        return evaluation;
    }
}
