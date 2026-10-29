package com.processVisualisation.virtualKitchen.ai.platform.evaluation;

import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AiRequestAnalytics;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AnalyticsStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * How step-image analytics records group into evaluable <em>image sets</em>: every image request of one recipe in one
 * run. Pure, so the grouping is unit-tested.
 * <ul>
 *     <li><b>Set key</b>: {@code recipeId::workflowId}; a run without a workflow uses its visualization job
 *     ({@code recipeId::job:<jobId>}), and a record with neither stands alone ({@code recipeId::req:<id>}).</li>
 *     <li><b>Images</b>: the set's SUCCEEDED requests for a step. The scene anchor ({@code stepId = scene}) is context,
 *     not scored. Failed and timed-out requests stay in the set because their cost counts.</li>
 *     <li><b>Model</b>: the model that produced most of the images (ties: first seen). A set with images from more than
 *     one model (fallback) is {@code mixedModels}.</li>
 * </ul>
 */
public final class EvaluationSets {

    public static final String SCENE_STEP = "scene";
    static final String NO_RECIPE = "unknown";
    static final String SEPARATOR = "::";

    private EvaluationSets() {
    }

    public static String setKey(AiRequestAnalytics record) {
        String recipe = record.getRecipeId() == null ? NO_RECIPE : record.getRecipeId();
        String run = record.getWorkflowId() != null ? record.getWorkflowId()
                : record.getJobId() != null ? "job:" + record.getJobId()
                : "req:" + record.getId();
        return recipe + SEPARATOR + run;
    }

    /** The parts of a set key; exactly one of workflowId, jobId and requestId is set. */
    public record KeyParts(String recipeId, String workflowId, String jobId, String requestId) {
    }

    public static KeyParts parse(String setKey) {
        int at = setKey == null ? -1 : setKey.indexOf(SEPARATOR);
        if (at <= 0 || at + SEPARATOR.length() >= setKey.length()) {
            throw new IllegalArgumentException("Not an image set key: " + setKey);
        }
        String recipe = setKey.substring(0, at);
        String run = setKey.substring(at + SEPARATOR.length());
        String recipeId = NO_RECIPE.equals(recipe) ? null : recipe;
        if (run.startsWith("job:")) return new KeyParts(recipeId, null, run.substring(4), null);
        if (run.startsWith("req:")) return new KeyParts(recipeId, null, null, run.substring(4));
        return new KeyParts(recipeId, run, null, null);
    }

    public static boolean isScene(AiRequestAnalytics record) {
        return SCENE_STEP.equals(record.getStepId());
    }

    public static boolean isImage(AiRequestAnalytics record) {
        return record.getStatus() == AnalyticsStatus.SUCCEEDED && record.getStepId() != null && !isScene(record);
    }

    public record ImageSet(String setKey, String recipeId, String workflowId, List<AiRequestAnalytics> rows) {

        public List<AiRequestAnalytics> images() {
            return rows.stream().filter(EvaluationSets::isImage).toList();
        }

        public Set<String> models() {
            Set<String> models = new LinkedHashSet<>();
            images().forEach(r -> {
                if (r.getResolvedModelKey() != null) models.add(r.getResolvedModelKey());
            });
            return models;
        }

        public boolean mixedModels() {
            return models().size() > 1;
        }

        /** The majority model of the images, or of every request when no image succeeded. */
        public String modelKey() {
            AiRequestAnalytics representative = representative();
            return representative == null ? null : representative.getResolvedModelKey();
        }

        public String provider() {
            AiRequestAnalytics representative = representative();
            return representative == null ? null : representative.getFinalProvider();
        }

        public String tier() {
            AiRequestAnalytics representative = representative();
            return representative == null ? null : representative.getModelTier();
        }

        public int failedRequests() {
            return (int) rows.stream()
                    .filter(r -> r.getStatus() == AnalyticsStatus.FAILED || r.getStatus() == AnalyticsStatus.TIMED_OUT)
                    .count();
        }

        public Instant startedAt() {
            return rows.stream().map(AiRequestAnalytics::getCreatedAt).filter(Objects::nonNull)
                    .min(Comparator.naturalOrder()).orElse(null);
        }

        /** First request created to last request completed; null when either end is unknown. */
        public Long spanMs() {
            Instant start = startedAt();
            Instant end = rows.stream().map(AiRequestAnalytics::getCompletedAt).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            return start == null || end == null || end.isBefore(start) ? null : end.toEpochMilli() - start.toEpochMilli();
        }

        private AiRequestAnalytics representative() {
            List<AiRequestAnalytics> pool = images().isEmpty() ? rows : images();
            Map<String, Integer> counts = new LinkedHashMap<>();
            Map<String, AiRequestAnalytics> first = new LinkedHashMap<>();
            for (AiRequestAnalytics row : pool) {
                if (row.getResolvedModelKey() == null) continue;
                counts.merge(row.getResolvedModelKey(), 1, Integer::sum);
                first.putIfAbsent(row.getResolvedModelKey(), row);
            }
            String best = null;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (best == null || entry.getValue() > counts.get(best)) best = entry.getKey();
            }
            return best == null ? null : first.get(best);
        }
    }

    /** Groups records into sets, newest set first. */
    public static List<ImageSet> group(Collection<AiRequestAnalytics> records) {
        Map<String, List<AiRequestAnalytics>> byKey = new LinkedHashMap<>();
        for (AiRequestAnalytics record : records) {
            byKey.computeIfAbsent(setKey(record), k -> new ArrayList<>()).add(record);
        }
        List<ImageSet> sets = new ArrayList<>();
        byKey.forEach((key, rows) -> sets.add(new ImageSet(key, rows.get(0).getRecipeId(), rows.get(0).getWorkflowId(), rows)));
        sets.sort(Comparator.comparing(ImageSet::startedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return sets;
    }
}
