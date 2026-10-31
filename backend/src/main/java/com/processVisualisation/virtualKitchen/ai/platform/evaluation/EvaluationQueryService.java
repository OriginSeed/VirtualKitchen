package com.processVisualisation.virtualKitchen.ai.platform.evaluation;

import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AiRequestAnalytics;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.OperationContext;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.query.AiResultResolver;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.query.AnalyticsCriteria;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.query.AnalyticsFilter;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.query.AnalyticsQueryService.Page;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationMath.ModelComparison;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationMath.SetCost;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationSets.ImageSet;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationSets.KeyParts;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.ImageEvaluation;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.SetEvaluation;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessType;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import com.processVisualisation.virtualKitchen.recipe.model.StepAnnotations;
import com.processVisualisation.virtualKitchen.recipe.repository.ProcessRepository;
import com.processVisualisation.virtualKitchen.recipe.service.ProcessStepOrder;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Read side of human evaluation: image sets built from step-image analytics records, joined with the stored scores.
 * Captured metrics (cost, time, model) are always read from {@code ai_request_analytics}, never copied. At most
 * {@value #MAX_ROWS} records are grouped per request; beyond that the response says it was truncated.
 */
@Service
public class EvaluationQueryService {

    static final int MAX_ROWS = 50_000;
    static final int MAX_PAGE_SIZE = 100;

    public enum SetStatus { UNSCORED, PARTIAL, SCORED }

    private static final String[] SET_FIELDS = {
            "_id", "status", "attemptCount", "transportAttempts", "costTotals", "usageTotals", "totalMs", "queueWaitMs",
            "validationPassed", "dataCompleteness", "finalProvider", "resolvedModelKey", "modelTier", "usedFallback",
            "workflowId", "jobId", "recipeId", "stepId", "createdAt", "completedAt", "outputRef"};

    private final MongoTemplate mongoTemplate;
    private final EvaluationStore store;
    private final ProcessRepository processRepository;
    private final AiResultResolver resultResolver;

    public EvaluationQueryService(MongoTemplate mongoTemplate, EvaluationStore store,
                                  ProcessRepository processRepository, AiResultResolver resultResolver) {
        this.mongoTemplate = mongoTemplate;
        this.store = store;
        this.processRepository = processRepository;
        this.resultResolver = resultResolver;
    }

    // --- set list ----------------------------------------------------------------------------------------------

    public record SetSummary(String setKey, String recipeId, String recipeName, String workflowId,
                             String modelKey, String provider, String tier, List<String> models, boolean mixedModels,
                             int imageCount, int failedRequests, Double totalCost, String currency, boolean costComplete,
                             Long spanMs, Instant startedAt,
                             SetStatus status, int scoredImages, String verdict, Double recipeScore,
                             Boolean accepted, Boolean hardFail) {
    }

    public record SetPage(Page<SetSummary> page, int setsScanned, boolean truncated) {
    }

    /**
     * Image sets matching the filter. {@code sort=unscored} puts unscored sets first, then partially scored ones;
     * otherwise newest first.
     */
    public SetPage listSets(EvaluationFilter filter, String sort, int page, int size) {
        Loaded loaded = load(filter);
        Map<String, SetEvaluation> setEvaluations = setEvaluations(loaded.sets());
        Map<String, Integer> scored = scoredImageCounts(loaded.sets());
        Map<String, String> names = recipeNames(loaded.sets());

        List<SetSummary> summaries = new ArrayList<>();
        for (ImageSet set : loaded.sets()) {
            SetSummary summary = summarize(set, setEvaluations.get(set.setKey()), scored.getOrDefault(set.setKey(), 0), names);
            if (filter.getStatus() == null || filter.getStatus().isEmpty()
                    || filter.getStatus().contains(summary.status().name())) {
                summaries.add(summary);
            }
        }
        if ("unscored".equalsIgnoreCase(sort)) {
            summaries.sort(Comparator.comparing(SetSummary::status)
                    .thenComparing(SetSummary::startedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        }
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int pageIndex = Math.max(0, page);
        List<SetSummary> items = summaries.stream().skip((long) pageIndex * pageSize).limit(pageSize).toList();
        return new SetPage(new Page<>(items, summaries.size(), pageIndex, pageSize), loaded.sets().size(), loaded.truncated());
    }

    // --- set detail --------------------------------------------------------------------------------------------

    public record ImageItem(String requestId, String stepId, Integer stepIndex, String processName, String stepTitle,
                            String stepDescription, String imagePrompt, String imageUrl, String provenance,
                            String modelKey, String provider, String tier, Boolean usedFallback,
                            Integer attemptCount, Double totalCost, String currency, Long totalMs, Long queueWaitMs,
                            Integer imageWidth, Integer imageHeight, Instant createdAt,
                            ImageEvaluation evaluation) {
    }

    public record SetDetail(SetSummary summary, String sceneImageUrl, List<ImageItem> images,
                            SetEvaluation evaluation, String rubricVersion) {
    }

    public Optional<SetDetail> setDetail(String setKey) {
        Optional<ImageSet> found = loadSet(setKey);
        if (found.isEmpty()) return Optional.empty();
        ImageSet set = found.get();

        Map<String, ImageEvaluation> evaluations = store.findImagesBySetKeys(List.of(setKey)).stream()
                .collect(Collectors.toMap(ImageEvaluation::getId, Function.identity(), (a, b) -> a));
        SetEvaluation setEvaluation = store.findSet(setKey).orElse(null);
        int scored = (int) evaluations.values().stream().filter(e -> e.getImageScore() != null).count();
        SetSummary summary = summarize(set, setEvaluation, scored, recipeNames(List.of(set)));

        Map<String, StepInfo> steps = stepInfo(set.recipeId());
        List<ImageItem> images = new ArrayList<>();
        for (AiRequestAnalytics row : set.images()) {
            StepInfo step = steps.get(nodeId(row.getStepId()));
            Optional<AiResultResolver.Result> result = resultResolver.resolve(row.getId(), null, false, true);
            AiRequestAnalytics.Cost cost = row.getCostTotals();
            AiRequestAnalytics.Usage usage = row.getUsageTotals();
            images.add(new ImageItem(row.getId(), row.getStepId(),
                    step == null ? null : step.index(), step == null ? null : step.processName(),
                    step == null ? null : step.title(), step == null ? null : step.description(),
                    result.map(AiResultResolver.Result::prompt).orElse(null),
                    result.map(AiResultResolver.Result::url).orElse(row.getOutputRef() == null ? null : row.getOutputRef().getUrl()),
                    result.map(r -> r.provenance() == null ? null : r.provenance().name()).orElse(null),
                    row.getResolvedModelKey(), row.getFinalProvider(), row.getModelTier(), row.getUsedFallback(),
                    row.getAttemptCount(), cost == null ? null : cost.getTotalCost(), cost == null ? null : cost.getCurrency(),
                    row.getTotalMs(), row.getQueueWaitMs(),
                    usage == null ? null : usage.getImageWidth(), usage == null ? null : usage.getImageHeight(),
                    row.getCreatedAt(), evaluations.get(row.getId())));
        }
        images.sort(Comparator.comparing(ImageItem::stepIndex, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ImageItem::createdAt, Comparator.nullsLast(Comparator.naturalOrder())));

        return Optional.of(new SetDetail(summary, sceneImageUrl(set), images, setEvaluation, EvaluationRubric.VERSION));
    }

    // --- comparison --------------------------------------------------------------------------------------------

    public record Comparison(List<ModelComparison> models, int setsScanned, boolean truncated, String rubricVersion) {
    }

    public Comparison compare(EvaluationFilter filter) {
        Loaded loaded = load(filter);
        List<String> keys = loaded.sets().stream().map(ImageSet::setKey).toList();
        List<ModelComparison> models = EvaluationMath.compare(loaded.sets(), setEvaluations(loaded.sets()),
                store.findImagesBySetKeys(keys), filter.isIncludeMixed());
        return new Comparison(models, loaded.sets().size(), loaded.truncated(), EvaluationRubric.VERSION);
    }

    // --- loading -----------------------------------------------------------------------------------------------

    /** The set with this key, read from analytics; empty when no step-image request belongs to it. */
    public Optional<ImageSet> loadSet(String setKey) {
        KeyParts parts = EvaluationSets.parse(setKey);
        Criteria criteria = where("operationContext").is(OperationContext.STEP_IMAGE_GENERATION.name())
                .and("recipeId").is(parts.recipeId());
        if (parts.workflowId() != null) criteria = criteria.and("workflowId").is(parts.workflowId());
        else if (parts.jobId() != null) criteria = criteria.and("jobId").is(parts.jobId()).and("workflowId").is(null);
        else criteria = criteria.and("_id").is(parts.requestId());
        Query query = Query.query(criteria);
        query.fields().include(SET_FIELDS);
        List<AiRequestAnalytics> rows = mongoTemplate.find(query, AiRequestAnalytics.class);
        return EvaluationSets.group(rows).stream().filter(s -> s.setKey().equals(setKey)).findFirst();
    }

    private record Loaded(List<ImageSet> sets, boolean truncated) {
    }

    private Loaded load(EvaluationFilter filter) {
        AnalyticsFilter analytics = new AnalyticsFilter();
        analytics.setFrom(filter.getFrom());
        analytics.setTo(filter.getTo());
        analytics.setRecipeId(filter.getRecipeId());
        analytics.setOperationContext(List.of(OperationContext.STEP_IMAGE_GENERATION.name()));
        Query query = Query.query(AnalyticsCriteria.build(analytics)).limit(MAX_ROWS + 1);
        query.fields().include(SET_FIELDS);

        List<AiRequestAnalytics> rows = new ArrayList<>();
        boolean truncated = false;
        try (Stream<AiRequestAnalytics> stream = mongoTemplate.stream(query, AiRequestAnalytics.class)) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                AiRequestAnalytics record = iterator.next();
                if (rows.size() == MAX_ROWS) {
                    truncated = true;
                    break;
                }
                rows.add(record);
            }
        }
        List<ImageSet> sets = EvaluationSets.group(rows).stream()
                .filter(s -> filter.getModel() == null || filter.getModel().isEmpty() || filter.getModel().contains(s.modelKey()))
                .filter(s -> filter.getTier() == null || filter.getTier().isEmpty() || filter.getTier().contains(s.tier()))
                .toList();
        return new Loaded(sets, truncated);
    }

    private SetSummary summarize(ImageSet set, SetEvaluation evaluation, int scoredImages, Map<String, String> names) {
        int imageCount = set.images().size();
        boolean verdict = evaluation != null && evaluation.getVerdict() != null;
        SetStatus status = verdict && scoredImages >= imageCount ? SetStatus.SCORED
                : verdict || scoredImages > 0 ? SetStatus.PARTIAL : SetStatus.UNSCORED;
        SetCost cost = EvaluationMath.setCost(set);
        return new SetSummary(set.setKey(), set.recipeId(),
                set.recipeId() == null ? null : names.getOrDefault(set.recipeId(), "Recipe #" + set.recipeId()),
                set.workflowId(), set.modelKey(), set.provider(), set.tier(), new ArrayList<>(set.models()),
                set.mixedModels(), imageCount, set.failedRequests(), cost.total(), cost.currency(), cost.complete(),
                set.spanMs(), set.startedAt(), status, scoredImages,
                verdict ? evaluation.getVerdict().name() : null,
                evaluation == null ? null : evaluation.getRecipeScore(),
                verdict ? evaluation.isAccepted() : null,
                evaluation == null ? null : evaluation.isHardFail());
    }

    private Map<String, SetEvaluation> setEvaluations(List<ImageSet> sets) {
        return store.findSets(sets.stream().map(ImageSet::setKey).toList()).stream()
                .collect(Collectors.toMap(SetEvaluation::getId, Function.identity(), (a, b) -> a));
    }

    private Map<String, Integer> scoredImageCounts(List<ImageSet> sets) {
        Map<String, Integer> counts = new HashMap<>();
        for (ImageEvaluation evaluation : store.findImagesBySetKeys(sets.stream().map(ImageSet::setKey).toList())) {
            if (evaluation.getImageScore() != null) counts.merge(evaluation.getSetKey(), 1, Integer::sum);
        }
        return counts;
    }

    /** Recipe names by id (only names are read). */
    private Map<String, String> recipeNames(List<ImageSet> sets) {
        List<Long> ids = sets.stream().map(ImageSet::recipeId).filter(id -> id != null && id.matches("\\d{1,18}"))
                .distinct().map(Long::valueOf).toList();
        Map<String, String> names = new HashMap<>();
        if (ids.isEmpty()) return names;
        Query query = Query.query(where("_id").in(ids));
        query.fields().include("_id", "name");
        List<Document> docs = mongoTemplate.find(query, Document.class, mongoTemplate.getCollectionName(RecipeTemplate.class));
        for (Document doc : docs == null ? List.<Document>of() : docs) {
            if (doc.get("_id") != null && doc.get("name") instanceof String name && !name.isBlank()) {
                names.put(doc.get("_id").toString(), name.trim());
            }
        }
        return names;
    }

    // --- recipe steps ------------------------------------------------------------------------------------------

    private record StepInfo(int index, String processName, String title, String description) {
    }

    /** A preparation flow's image is keyed {@code flow::<outputStepId>}; its step is that node. */
    static String nodeId(String stepId) {
        return stepId != null && stepId.startsWith("flow::") ? stepId.substring("flow::".length()) : stepId;
    }

    /** Every step of the recipe in cooking order: preparation processes first, then the main process. */
    private Map<String, StepInfo> stepInfo(String recipeId) {
        Map<String, StepInfo> steps = new LinkedHashMap<>();
        if (recipeId == null || !recipeId.matches("\\d{1,18}")) return steps;
        List<Process> processes = new ArrayList<>(processRepository.findByRecipeId(Long.valueOf(recipeId)));
        processes.sort(Comparator.comparing(p -> p.getType() == ProcessType.MAIN));
        int index = 0;
        for (Process process : processes) {
            for (Process.ProcessNode node : ProcessStepOrder.orderedSteps(process)) {
                Map<String, Object> fields = stepFields(node.getData());
                steps.put(node.getId(), new StepInfo(++index, process.getName(),
                        text(fields.get("expectedOutput")), StepAnnotations.toPlainText(text(fields.get("actionDescription")))));
            }
        }
        return steps;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stepFields(Map<String, Object> data) {
        if (data == null) return Map.of();
        Object nested = data.get("step");
        return nested instanceof Map ? (Map<String, Object>) nested : data;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString().trim();
    }

    /** The set's own scene-anchor image, else the recipe's current anchor (it is generated once per recipe). */
    private String sceneImageUrl(ImageSet set) {
        for (AiRequestAnalytics row : set.rows()) {
            if (EvaluationSets.isScene(row) && row.getOutputRef() != null && row.getOutputRef().getUrl() != null) {
                return row.getOutputRef().getUrl();
            }
        }
        if (set.recipeId() == null) return null;
        Document asset = mongoTemplate.findOne(
                Query.query(where("visualizationKey").is(set.recipeId() + EvaluationSets.SEPARATOR + EvaluationSets.SCENE_STEP)),
                Document.class, "visualization_asset");
        return asset == null ? null : asset.getString("imageUrl");
    }
}
