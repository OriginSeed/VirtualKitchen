package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import com.processVisualisation.virtualKitchen.ai.platform.dispatch.AiClientResolver;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestQueueService;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.response.AiJsonResponseSupport;
import com.processVisualisation.virtualKitchen.ai.provider.AiClient;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiRequest;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiResponse;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutputSchema;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutputSchema.Field;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationPromptBuilder.BatchTarget;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService.VisualPreparation;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService.VisualTarget;
import com.processVisualisation.virtualKitchen.common.logging.FailureLogger;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes the image prompts for a whole recipe in one text call, ahead of the per-step image work, so every image of
 * the recipe shares one style (one kitchen, the same cookware and lighting) and the recipe pays for one prompt request
 * instead of one per image. The prompts are stored on each target's {@link com.processVisualisation.virtualKitchen.ai.asset.VisualizationAsset},
 * where {@link RecipeProcessVisualizationService#resolveTarget} finds them and only generates the image.
 * <p>
 * Best effort: a target the batch leaves without a prompt (left out of the answer, or the call failed) simply falls
 * back to the per-step prompt call inside {@code resolveTarget}, so a batch problem never fails a visuals job.
 * <p>
 * The visuals jobs started together for a recipe (one per process) each call {@link #ensurePrompts} with the same
 * scope; calls for one recipe run one at a time, so the first writes every prompt and the others find nothing left.
 */
@Service
public class VisualizationPromptBatchService {

    private static final Logger log = LoggerFactory.getLogger(VisualizationPromptBatchService.class);

    static final String CORRELATION_TYPE = "process-visualization-prompt-batch";

    /** Most images one call writes prompts for; a larger recipe is split into several calls. */
    public static final int MAX_TARGETS_PER_REQUEST = 24;

    /** Existing prompts of the recipe shown to the model so new prompts match them. */
    static final int MAX_STYLE_REFERENCES = 2;

    private static final int BASE_MAX_TOKENS = 800;
    private static final int MAX_TOKENS_PER_TARGET = 300;
    private static final int MAX_TOKENS_CAP = 8000;

    private final RecipeProcessVisualizationService visualizationService;
    private final RecipeProcessVisualizationPromptBuilder promptBuilder;
    private final AiRequestQueueService queueService;
    private final AiClientResolver clientResolver;
    private final AiJsonResponseSupport responseSupport;
    private final Map<String, Object> responseSchema = buildResponseSchema();

    private final Map<Long, Object> recipeLocks = new ConcurrentHashMap<>();

    public VisualizationPromptBatchService(
            RecipeProcessVisualizationService visualizationService,
            RecipeProcessVisualizationPromptBuilder promptBuilder,
            AiRequestQueueService queueService,
            AiClientResolver clientResolver,
            AiJsonResponseSupport responseSupport
    ) {
        this.visualizationService = visualizationService;
        this.promptBuilder = promptBuilder;
        this.queueService = queueService;
        this.clientResolver = clientResolver;
        this.responseSupport = responseSupport;
    }

    /** The model's answer: the shared style it chose, and one prompt per target id. */
    record BatchOutput(String styleGuide, List<Entry> prompts) {
        record Entry(String id, String imagePrompt) {
        }
    }

    /**
     * Writes and stores a prompt for every visual target of {@code scopeProcessIds} that has none yet. Never throws:
     * whatever it couldn't write is left to the per-step fallback.
     *
     * @param scopeProcessIds the recipe's processes being visualized together (MAIN and/or its Preparation)
     * @return how many prompts were written and stored
     */
    public int ensurePrompts(Long userId, Long recipeId, List<Long> scopeProcessIds) {
        try {
            synchronized (recipeLocks.computeIfAbsent(recipeId, id -> new Object())) {
                return writeMissingPrompts(userId, recipeId, scopeProcessIds);
            }
        } catch (Exception e) {
            FailureLogger.logFailure(log, "visualization_prompt_batch_failed", e,
                    "recipeId=" + recipeId + " fallback=per_step_prompts");
            return 0;
        }
    }

    private int writeMissingPrompts(Long userId, Long recipeId, List<Long> scopeProcessIds) {
        List<VisualTarget> flows = new ArrayList<>();
        List<VisualTarget> steps = new ArrayList<>();
        for (Long processId : scopeProcessIds) {
            VisualPreparation preparation = visualizationService.prepareVisualTargets(processId);
            Long owningRecipeId = preparation.process().getRecipeId();
            if (owningRecipeId != null && !owningRecipeId.equals(recipeId)) continue;
            boolean isPreparation = preparation.process().getType() == ProcessType.SUBPROCESS;
            (isPreparation ? flows : steps).addAll(preparation.targets());
        }

        // Preparations first, then MAIN in cooking order — the order the model reads the recipe in.
        List<BatchTarget> missing = new ArrayList<>();
        Map<String, String> keyById = new LinkedHashMap<>();
        List<String> styleReferences = new ArrayList<>();
        collect(recipeId, flows, "p", missing, keyById, styleReferences);
        collect(recipeId, steps, "m", missing, keyById, styleReferences);
        if (missing.isEmpty()) {
            log.debug("event=visualization_prompt_batch_skipped recipeId={} reason=no_missing_prompts", recipeId);
            return 0;
        }

        int written = 0;
        for (int start = 0; start < missing.size(); start += MAX_TARGETS_PER_REQUEST) {
            List<BatchTarget> chunk = missing.subList(start, Math.min(missing.size(), start + MAX_TARGETS_PER_REQUEST));
            List<String> references = styleReferences.subList(0, Math.min(MAX_STYLE_REFERENCES, styleReferences.size()));
            Map<String, String> prompts = requestPrompts(userId, recipeId, chunk, List.copyOf(references));
            for (BatchTarget target : chunk) {
                String prompt = prompts.get(target.id());
                if (!StringUtils.hasText(prompt)) continue;
                visualizationService.savePrompt(keyById.get(target.id()), prompt.trim());
                styleReferences.add(prompt.trim());
                written++;
            }
        }
        log.info("event=visualization_prompt_batch_completed recipeId={} promptVersion={} targets={} written={} fallbackTargets={}",
                recipeId, RecipeProcessVisualizationPromptBuilder.BATCH_PROMPT_VERSION, missing.size(), written,
                missing.size() - written);
        return written;
    }

    /** Numbers the targets that still need a prompt; ones that have one become style references instead. */
    private void collect(Long recipeId, List<VisualTarget> targets, String prefix, List<BatchTarget> missing,
                         Map<String, String> keyById, List<String> styleReferences) {
        int index = 0;
        for (VisualTarget target : targets) {
            String key = RecipeProcessVisualizationService.visualizationKey(recipeId, target);
            var existing = visualizationService.existingPrompt(key);
            if (existing.isPresent()) {
                styleReferences.add(existing.get());
                continue;
            }
            String id = prefix + (++index);
            missing.add(new BatchTarget(id, target));
            keyById.put(id, key);
        }
    }

    /** One provider call for a chunk; the prompts it answered by id (empty when the answer couldn't be read). */
    private Map<String, String> requestPrompts(Long userId, Long recipeId, List<BatchTarget> chunk, List<String> references) {
        String userPrompt = promptBuilder.buildBatchUserPrompt(chunk, references);
        return queueService.executeBounded(
                userId,
                AiCapability.TEXT_TO_TEXT,
                null,
                null,
                CORRELATION_TYPE,
                String.valueOf(recipeId),
                selection -> {
                    AiClient client = clientResolver.resolveTextClient(selection.model());
                    AiRequest request = AiRequest.builder()
                            .model(selection.model().getProviderModelId())
                            .systemPrompt(promptBuilder.buildBatchSystemPrompt())
                            .userPrompt(userPrompt)
                            .temperature(0.4d)
                            .maxTokens(Math.min(MAX_TOKENS_CAP, BASE_MAX_TOKENS + MAX_TOKENS_PER_TARGET * chunk.size()))
                            .responseFormat("json_object")
                            .responseSchema(responseSchema)
                            .responseSchemaName("visualization_prompts")
                            .operation("VISUALIZATION_PROMPT_BATCH")
                            .build();
                    long startNs = System.nanoTime();
                    AiResponse response = client.chat(request);
                    long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
                    return readPrompts(userPrompt, response, durationMs, chunk);
                }
        ).value();
    }

    private Map<String, String> readPrompts(String userPrompt, AiResponse response, long durationMs, List<BatchTarget> chunk) {
        String content = response == null ? null : response.getContent();
        Map<String, String> prompts = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        if (!StringUtils.hasText(content)) {
            errors.add("AI response content is empty");
        } else {
            try {
                BatchOutput output = responseSupport.readValue(content, BatchOutput.class);
                List<String> ids = chunk.stream().map(BatchTarget::id).toList();
                for (BatchOutput.Entry entry : output.prompts() == null ? List.<BatchOutput.Entry>of() : output.prompts()) {
                    if (entry == null || !ids.contains(entry.id()) || !StringUtils.hasText(entry.imagePrompt())) continue;
                    prompts.putIfAbsent(entry.id(), entry.imagePrompt());
                }
                ids.stream().filter(id -> !prompts.containsKey(id))
                        .forEach(id -> errors.add("No prompt for " + id));
            } catch (Exception e) {
                errors.add("Invalid JSON format: " + e.getMessage());
            }
        }
        // A partial answer is still used; the targets it missed fall back to per-step prompts.
        responseSupport.persistAIResponse(CORRELATION_TYPE, userPrompt, response, !prompts.isEmpty(), durationMs, errors);
        return prompts;
    }

    private static Map<String, Object> buildResponseSchema() {
        Map<String, Object> entry = RecipeProcessOutputSchema.object(List.of(
                new Field("id", RecipeProcessOutputSchema.string(), true),
                new Field("imagePrompt", RecipeProcessOutputSchema.string(), true)));
        return RecipeProcessOutputSchema.object(List.of(
                new Field("styleGuide", RecipeProcessOutputSchema.string(), true),
                new Field("prompts", RecipeProcessOutputSchema.arrayOf(entry), true)));
    }
}
