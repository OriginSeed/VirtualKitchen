package com.processVisualisation.virtualKitchen.ai.platform.evaluation;

import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AiRequestAnalytics;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AnalyticsStatus;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.CostBasis;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.ImageEvaluation;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.SetEvaluation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builders and an in-memory {@link EvaluationStore} for evaluation tests. */
final class EvaluationTestSupport {

    static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    private EvaluationTestSupport() {
    }

    /** A step-image analytics record; {@code cost} null means it could not be costed. */
    static AiRequestAnalytics row(String id, String recipeId, String workflowId, String stepId, String model,
                                  AnalyticsStatus status, Double cost, long startSec, long totalMs) {
        AiRequestAnalytics record = new AiRequestAnalytics();
        record.setId(id);
        record.setRecipeId(recipeId);
        record.setWorkflowId(workflowId);
        record.setStepId(stepId);
        record.setResolvedModelKey(model);
        record.setFinalProvider(model == null ? null : model.split("-")[0]);
        record.setModelTier(model != null && model.startsWith("gemini") ? "PAID" : "OPEN_SOURCE");
        record.setStatus(status);
        record.setCreatedAt(T0.plusSeconds(startSec));
        record.setCompletedAt(T0.plusSeconds(startSec).plusMillis(totalMs));
        record.setTotalMs(totalMs);
        record.setQueueWaitMs(100L);
        if (cost != null) {
            AiRequestAnalytics.Cost total = new AiRequestAnalytics.Cost();
            total.setTotalCost(cost);
            total.setCurrency("USD");
            total.setCostBasis(CostBasis.ESTIMATED);
            record.setCostTotals(total);
        }
        return record;
    }

    static AiRequestAnalytics image(String id, String recipeId, String workflowId, String stepId, String model,
                                    double cost, long startSec, long totalMs) {
        return row(id, recipeId, workflowId, stepId, model, AnalyticsStatus.SUCCEEDED, cost, startSec, totalMs);
    }

    static ImageEvaluation scored(String id, String setKey, String model, int rating, List<EvaluationRubric.DefectTag> tags) {
        ImageEvaluation evaluation = new ImageEvaluation();
        evaluation.setId(id);
        evaluation.setSetKey(setKey);
        evaluation.setModelKey(model);
        evaluation.setConsistency(rating);
        evaluation.setStepCorrectness(rating);
        evaluation.setStateContinuity(rating);
        evaluation.setRealism(rating);
        evaluation.setDefectTags(new ArrayList<>(tags));
        evaluation.setImageScore(EvaluationScoring.imageScore(rating, rating, rating, rating, tags));
        return evaluation;
    }

    /**
     * Keeps each record's stored revision apart from the (shared, mutable) instance, so a replace is checked against
     * what was last written, as MongoDB's compare-and-set would.
     */
    static final class InMemoryEvaluationStore implements EvaluationStore {
        final Map<String, ImageEvaluation> images = new LinkedHashMap<>();
        final Map<String, SetEvaluation> sets = new LinkedHashMap<>();
        final Map<String, Long> imageRevisions = new LinkedHashMap<>();
        final Map<String, Long> setRevisions = new LinkedHashMap<>();

        @Override
        public Optional<ImageEvaluation> findImage(String id) {
            return Optional.ofNullable(images.get(id));
        }

        @Override
        public List<ImageEvaluation> findImagesBySetKeys(Collection<String> setKeys) {
            return images.values().stream().filter(i -> setKeys.contains(i.getSetKey())).toList();
        }

        @Override
        public boolean insertImage(ImageEvaluation evaluation) {
            if (images.putIfAbsent(evaluation.getId(), evaluation) != null) return false;
            imageRevisions.put(evaluation.getId(), evaluation.getRevision());
            return true;
        }

        @Override
        public boolean replaceImage(ImageEvaluation evaluation, long expectedRevision) {
            Long stored = imageRevisions.get(evaluation.getId());
            if (stored == null || stored != expectedRevision) return false;
            images.put(evaluation.getId(), evaluation);
            imageRevisions.put(evaluation.getId(), evaluation.getRevision());
            return true;
        }

        @Override
        public Optional<SetEvaluation> findSet(String setKey) {
            return Optional.ofNullable(sets.get(setKey));
        }

        @Override
        public List<SetEvaluation> findSets(Collection<String> setKeys) {
            return sets.values().stream().filter(s -> setKeys.contains(s.getId())).toList();
        }

        @Override
        public boolean insertSet(SetEvaluation evaluation) {
            if (sets.putIfAbsent(evaluation.getId(), evaluation) != null) return false;
            setRevisions.put(evaluation.getId(), evaluation.getRevision());
            return true;
        }

        @Override
        public boolean replaceSet(SetEvaluation evaluation, long expectedRevision) {
            Long stored = setRevisions.get(evaluation.getId());
            if (stored == null || stored != expectedRevision) return false;
            sets.put(evaluation.getId(), evaluation);
            setRevisions.put(evaluation.getId(), evaluation.getRevision());
            return true;
        }
    }
}
