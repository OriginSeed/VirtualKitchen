package com.processVisualisation.virtualKitchen.ai.platform.evaluation;

import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AiRequestAnalytics;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.query.AnalyticsMath;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.query.AnalyticsMath.GroupMetrics;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.query.AnalyticsMath.Row;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.query.AnalyticsMath.Stat;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationRubric.DefectTag;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationRubric.Dimension;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationSets.ImageSet;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.ImageEvaluation;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.SetEvaluation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Per-model comparison of quality, cost and time. Pure, so every definition is unit-tested. The three axes are kept
 * apart and each reports its own sample size:
 * <ul>
 *     <li><b>Cost</b> (every set of the model in range): cost per image is the mean cost of a successful image
 *     request; cost per set is a set's total cost, failed attempts, retries and the scene anchor included, over sets
 *     whose cost is fully known. Costs in different currencies are never combined.</li>
 *     <li><b>Time</b>: image p50/p95 is a successful image request's total time; set p50/p95 is first request
 *     created to last request completed.</li>
 *     <li><b>Quality</b> (sets with at least one scored image): mean recipe score, per-dimension mean rating (1–5),
 *     defect rate (scored images with any defect tag), acceptance and hard-fail rates over sets with a verdict.</li>
 *     <li><b>Cost per accepted set</b>: total cost of the sets with a verdict ÷ accepted sets — what one usable
 *     recipe really costs once rejected ones are paid for too.</li>
 *     <li><b>Quality per cost</b>: mean recipe score ÷ mean cost per set.</li>
 * </ul>
 */
public final class EvaluationMath {

    private EvaluationMath() {
    }

    public record TagCount(String tag, int count) {
    }

    /** Cost of one set: summed over its costed requests; null when any request could not be costed. */
    public record SetCost(Double total, String currency, boolean complete) {
    }

    public record ModelComparison(
            String modelKey, String provider, String tier,
            int sets, int images, int failedRequests,
            String currency, Stat costPerImage, Stat costPerSet, Double costPerAcceptedSet,
            Stat p50ImageMs, Stat p95ImageMs, Stat p50SetMs, Stat p95SetMs, Stat avgQueueWaitMs,
            int scoredSets, int scoredImages, int verdictSets,
            Stat recipeScore, Double acceptanceRate, Double hardFailRate,
            Map<String, Stat> dimensions, Double defectRate, List<TagCount> topDefects,
            Double qualityPerCost, boolean lowSample) {
    }

    public static SetCost setCost(ImageSet set) {
        GroupMetrics metrics = AnalyticsMath.summarize(Map.of(), set.rows().stream().map(r -> Row.of(r, Map.of())).toList());
        String currency = metrics.currencies().size() == 1 ? metrics.currencies().iterator().next() : null;
        return new SetCost(metrics.totalCost(), currency, metrics.costComplete());
    }

    public static List<ModelComparison> compare(List<ImageSet> sets, Map<String, SetEvaluation> setEvaluations,
                                                Collection<ImageEvaluation> imageEvaluations, boolean includeMixed) {
        Map<String, List<ImageSet>> byModel = new LinkedHashMap<>();
        for (ImageSet set : sets) {
            if (set.modelKey() == null || (!includeMixed && set.mixedModels())) continue;
            byModel.computeIfAbsent(set.modelKey(), k -> new ArrayList<>()).add(set);
        }
        Map<String, List<ImageEvaluation>> imagesBySet = new LinkedHashMap<>();
        for (ImageEvaluation evaluation : imageEvaluations) {
            imagesBySet.computeIfAbsent(evaluation.getSetKey(), k -> new ArrayList<>()).add(evaluation);
        }

        List<ModelComparison> result = new ArrayList<>();
        byModel.forEach((model, modelSets) -> result.add(compareModel(model, modelSets, setEvaluations, imagesBySet)));
        result.sort(Comparator.comparing(ModelComparison::sets, Comparator.reverseOrder()));
        return result;
    }

    private static ModelComparison compareModel(String model, List<ImageSet> sets, Map<String, SetEvaluation> setEvaluations,
                                                Map<String, List<ImageEvaluation>> imagesBySet) {
        ImageSet first = sets.get(0);
        Set<String> currencies = new LinkedHashSet<>();
        List<Double> imageCosts = new ArrayList<>();
        List<Double> setCosts = new ArrayList<>();
        List<Double> imageMs = new ArrayList<>();
        List<Double> setMs = new ArrayList<>();
        List<Double> queueWaits = new ArrayList<>();
        int images = 0, failed = 0;
        Map<String, SetCost> costBySet = new LinkedHashMap<>();

        for (ImageSet set : sets) {
            failed += set.failedRequests();
            for (AiRequestAnalytics image : set.images()) {
                images++;
                AiRequestAnalytics.Cost cost = image.getCostTotals();
                if (cost != null && cost.getTotalCost() != null) {
                    imageCosts.add(cost.getTotalCost());
                    if (cost.getCurrency() != null) currencies.add(cost.getCurrency());
                }
                if (image.getTotalMs() != null) imageMs.add(image.getTotalMs().doubleValue());
                if (image.getQueueWaitMs() != null) queueWaits.add(image.getQueueWaitMs().doubleValue());
            }
            SetCost cost = setCost(set);
            costBySet.put(set.setKey(), cost);
            if (cost.complete() && cost.total() != null) {
                setCosts.add(cost.total());
                if (cost.currency() != null) currencies.add(cost.currency());
            }
            if (set.spanMs() != null) setMs.add(set.spanMs().doubleValue());
        }
        boolean mixedCurrency = currencies.size() > 1;

        // --- quality ---
        List<Double> recipeScores = new ArrayList<>();
        List<ImageEvaluation> scoredImages = new ArrayList<>();
        int scoredSets = 0, verdictSets = 0, accepted = 0, hardFails = 0;
        double verdictCost = 0;
        boolean verdictCostKnown = true;
        for (ImageSet set : sets) {
            List<ImageEvaluation> evaluated = imagesBySet.getOrDefault(set.setKey(), List.of()).stream()
                    .filter(e -> e.getImageScore() != null).toList();
            if (!evaluated.isEmpty()) {
                scoredSets++;
                scoredImages.addAll(evaluated);
            }
            SetEvaluation setEvaluation = setEvaluations.get(set.setKey());
            if (setEvaluation != null && setEvaluation.getRecipeScore() != null) {
                recipeScores.add(setEvaluation.getRecipeScore());
            }
            if (setEvaluation != null && setEvaluation.getVerdict() != null) {
                verdictSets++;
                if (setEvaluation.isAccepted()) accepted++;
                if (setEvaluation.isHardFail()) hardFails++;
                SetCost cost = costBySet.get(set.setKey());
                if (cost.complete() && cost.total() != null) verdictCost += cost.total();
                else verdictCostKnown = false;
            }
        }

        Map<String, Stat> dimensions = new LinkedHashMap<>();
        dimensions.put(Dimension.CONSISTENCY.name(), ratingMean(scoredImages, ImageEvaluation::getConsistency));
        dimensions.put(Dimension.STEP_CORRECTNESS.name(), ratingMean(scoredImages, ImageEvaluation::getStepCorrectness));
        dimensions.put(Dimension.STATE_CONTINUITY.name(), ratingMean(scoredImages, ImageEvaluation::getStateContinuity));
        dimensions.put(Dimension.DEFECTS.name(),
                ratingMean(scoredImages, e -> EvaluationScoring.defectRating(e.getDefectTags())));
        dimensions.put(Dimension.REALISM.name(), ratingMean(scoredImages, ImageEvaluation::getRealism));

        Map<DefectTag, Integer> tagCounts = new LinkedHashMap<>();
        int withDefects = 0;
        for (ImageEvaluation evaluation : scoredImages) {
            List<DefectTag> tags = evaluation.getDefectTags() == null ? List.of() : evaluation.getDefectTags();
            if (!tags.isEmpty()) withDefects++;
            tags.forEach(tag -> tagCounts.merge(tag, 1, Integer::sum));
        }
        List<TagCount> topDefects = tagCounts.entrySet().stream()
                .sorted(Map.Entry.<DefectTag, Integer>comparingByValue().reversed())
                .limit(3)
                .map(e -> new TagCount(e.getKey().name(), e.getValue()))
                .toList();

        Stat costPerSet = mixedCurrency ? none() : AnalyticsMath.mean(setCosts);
        Stat recipeScore = AnalyticsMath.mean(recipeScores);
        Double costPerAccepted = mixedCurrency || !verdictCostKnown || accepted == 0 ? null : verdictCost / accepted;
        Double qualityPerCost = recipeScore.value() == null || costPerSet.value() == null || costPerSet.value() <= 0
                ? null : recipeScore.value() / costPerSet.value();

        return new ModelComparison(
                model, first.provider(), first.tier(),
                sets.size(), images, failed,
                mixedCurrency || currencies.isEmpty() ? null : currencies.iterator().next(),
                mixedCurrency ? none() : AnalyticsMath.mean(imageCosts), costPerSet, costPerAccepted,
                AnalyticsMath.percentile(imageMs, 50), AnalyticsMath.percentile(imageMs, 95),
                AnalyticsMath.percentile(setMs, 50), AnalyticsMath.percentile(setMs, 95), AnalyticsMath.mean(queueWaits),
                scoredSets, scoredImages.size(), verdictSets,
                recipeScore, ratio(accepted, verdictSets), ratio(hardFails, verdictSets),
                dimensions, ratio(withDefects, scoredImages.size()), topDefects,
                qualityPerCost, scoredSets < EvaluationRubric.LOW_SAMPLE_SETS);
    }

    private static Stat ratingMean(List<ImageEvaluation> evaluations, Function<ImageEvaluation, Integer> rating) {
        return AnalyticsMath.mean(evaluations.stream().map(rating).filter(Objects::nonNull).map(Integer::doubleValue).toList());
    }

    private static Stat none() {
        return new Stat(null, 0);
    }

    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }
}
