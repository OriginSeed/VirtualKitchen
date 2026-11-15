package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import com.processVisualisation.virtualKitchen.ai.platform.config.AiGenerationParamsProperties;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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


    private final RecipeProcessVisualizationService visualizationService;
    private final RecipeProcessVisualizationPromptBuilder promptBuilder;
    private final AiRequestQueueService queueService;
    private final AiClientResolver clientResolver;
    private final AiJsonResponseSupport responseSupport;
    /** Asked on every batch: an admin can switch scene anchors on or off at runtime. */
    private final VisualizationSceneAnchorService sceneAnchorService;
    private final Map<String, Object> responseSchema = buildResponseSchema();
    private final Map<String, Object> focusedResponseSchema = buildFocusedResponseSchema();

    private final Map<Long, Object> recipeLocks = new ConcurrentHashMap<>();

    public VisualizationPromptBatchService(
            RecipeProcessVisualizationService visualizationService,
            RecipeProcessVisualizationPromptBuilder promptBuilder,
            AiRequestQueueService queueService,
            AiClientResolver clientResolver,
            AiJsonResponseSupport responseSupport,
            VisualizationSceneAnchorService sceneAnchorService
    ) {
        this.visualizationService = visualizationService;
        this.promptBuilder = promptBuilder;
        this.queueService = queueService;
        this.clientResolver = clientResolver;
        this.responseSupport = responseSupport;
        this.sceneAnchorService = sceneAnchorService;
    }

    /** Sampling parameters; admin runtime overrides apply to the next request. Defaults when not injected (tests). */
    private AiGenerationParamsProperties generationParams = new AiGenerationParamsProperties();

    @Autowired
    void setGenerationParams(AiGenerationParamsProperties generationParams) {
        this.generationParams = generationParams;
    }

    /** The model's answer: the shared style it chose, the scene (when asked for), and one prompt per target id. */
    record BatchOutput(String styleGuide, String dishContext, String scenePrompt, List<Entry> prompts) {
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

    /** The focused batch answer: the recipe's equipment (when asked for) and one {@link ActionContext} per target id. */
    record FocusedOutput(String dishContext, SceneContext scene, List<Entry> prompts) {
        record Entry(String id, String focus, String state, String action, Boolean sameAsPrevious) {

            Entry(String id, String focus, String state, String action) {
                this(id, focus, state, action, null);
            }
        }
    }

    /** What one focused call answered, by id; scene fields are null unless asked for and answered. */
    private record FocusedAnswer(String dishContext, SceneContext scene, Map<String, FocusedOutput.Entry> entries) {
    }

    /**
     * The Focused Cooking View's batch: the text model names the recipe's equipment once and describes only the food
     * and the action of each image; every prompt is stored with its {@link VisualFocus}, and the scene is added when
     * the image is requested. Targets that already have a prompt (of either kind) are left alone.
     */
    private int writeMissingFocusedPrompts(Long userId, Long recipeId, List<VisualTarget> flows, List<VisualTarget> steps,
                                           String dishName) {
        List<BatchTarget> missing = new ArrayList<>();
        Map<String, String> keyById = new LinkedHashMap<>();
        List<String> ignoredReferences = new ArrayList<>();
        collect(recipeId, flows, "p", missing, keyById, ignoredReferences);
        collect(recipeId, steps, "m", missing, keyById, ignoredReferences);
        Map<String, VisualFocus> focusById = new LinkedHashMap<>();
        missing.forEach(target -> focusById.put(target.id(), visualizationService.defaultFocus(target.target())));
        Set<String> firstOfGroup = firstOfGroup(flows, steps);

        // The equipment is written once per recipe, with or without a scene anchor: every focused prompt uses it.
        boolean sceneNeeded = visualizationService.findScene(recipeId)
                .map(com.processVisualisation.virtualKitchen.ai.asset.VisualizationAsset::getSceneContext).isEmpty()
                && (!missing.isEmpty() || anyImageMissing(recipeId, flows) || anyImageMissing(recipeId, steps));
        if (missing.isEmpty() && !sceneNeeded) {
            log.debug("event=visualization_prompt_batch_skipped recipeId={} reason=no_missing_prompts", recipeId);
            return 0;
        }

        int written = 0;
        boolean sceneWritten = false;
        int start = 0;
        do {
            List<BatchTarget> chunk = missing.subList(start, Math.min(missing.size(), start + MAX_TARGETS_PER_REQUEST));
            boolean askScene = sceneNeeded && start == 0;
            FocusedAnswer answer = requestFocusedPrompts(userId, recipeId, chunk, focusById, dishName, askScene);
            if (askScene && answer.scene() != null) {
                visualizationService.saveSceneContext(recipeId, answer.scene(), answer.dishContext());
                sceneWritten = true;
            }
            for (BatchTarget target : chunk) {
                FocusedOutput.Entry entry = answer.entries().get(target.id());
                if (entry == null) continue;
                VisualFocus defaultFocus = focusById.get(target.id());
                // A storyboard stays one; otherwise the writer may move the camera to where the step happens.
                VisualFocus focus = defaultFocus == VisualFocus.STORYBOARD
                        ? defaultFocus
                        : VisualFocus.parse(entry.focus()).orElse(defaultFocus);
                String text = new ActionContext(focus, entry.state(), entry.action()).describe();
                if (!StringUtils.hasText(text)) continue;
                // The first step of a flow (or of the main steps) has nothing before it to look like.
                boolean reusesPrevious = Boolean.TRUE.equals(entry.sameAsPrevious())
                        && target.target().flow() == null
                        && !firstOfGroup.contains(target.target().nodeId());
                visualizationService.savePrompt(keyById.get(target.id()), text, focus, reusesPrevious);
                written++;
            }
            start += MAX_TARGETS_PER_REQUEST;
        } while (start < missing.size());
        log.info("event=visualization_prompt_batch_completed recipeId={} promptVersion={} focused=true targets={} written={} fallbackTargets={} sceneRequested={} sceneWritten={}",
                recipeId, RecipeProcessVisualizationPromptBuilder.FOCUSED_BATCH_PROMPT_VERSION, missing.size(), written,
                missing.size() - written, sceneNeeded, sceneWritten);
        return written;
    }

    private FocusedAnswer requestFocusedPrompts(Long userId, Long recipeId, List<BatchTarget> chunk,
                                                Map<String, VisualFocus> focusById, String dishName, boolean askScene) {
        String userPrompt = promptBuilder.buildFocusedBatchUserPrompt(chunk, focusById, dishName, askScene);
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
                            .systemPrompt(promptBuilder.buildFocusedBatchSystemPrompt())
                            .userPrompt(userPrompt)
                            .temperature(generationParams.getPromptBatch().getTemperature())
                            .maxTokens(generationParams.getPromptBatch().maxTokensFor(chunk.size()))
                            .responseFormat("json_object")
                            .responseSchema(focusedResponseSchema)
                            .responseSchemaName("visualization_focused_prompts")
                            .operation("VISUALIZATION_PROMPT_BATCH")
                            .build();
                    long startNs = System.nanoTime();
                    AiResponse response = client.chat(request);
                    long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
                    return readFocusedPrompts(userPrompt, response, durationMs, chunk, askScene);
                }
        ).value();
    }

    private FocusedAnswer readFocusedPrompts(String userPrompt, AiResponse response, long durationMs,
                                             List<BatchTarget> chunk, boolean askScene) {
        String content = response == null ? null : response.getContent();
        Map<String, FocusedOutput.Entry> entries = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        FocusedOutput output = null;
        if (!StringUtils.hasText(content)) {
            errors.add("AI response content is empty");
        } else {
            try {
                output = responseSupport.readValue(content, FocusedOutput.class);
                List<String> ids = chunk.stream().map(BatchTarget::id).toList();
                for (FocusedOutput.Entry entry : output.prompts() == null ? List.<FocusedOutput.Entry>of() : output.prompts()) {
                    if (entry == null || !ids.contains(entry.id())) continue;
                    if (!StringUtils.hasText(entry.state()) && !StringUtils.hasText(entry.action())) continue;
                    entries.putIfAbsent(entry.id(), entry);
                }
                ids.stream().filter(id -> !entries.containsKey(id))
                        .forEach(id -> errors.add("No prompt for " + id));
            } catch (Exception e) {
                output = null;
                errors.add("Invalid JSON format: " + e.getMessage());
            }
        }
        boolean sceneAnswered = askScene && output != null && output.scene() != null;
        if (askScene && output != null && !sceneAnswered) errors.add("No scene");
        // A partial answer is still used; the targets it missed fall back to per-step prompts.
        responseSupport.persistAIResponse(CORRELATION_TYPE, userPrompt, response, !entries.isEmpty() || sceneAnswered,
                durationMs, errors);
        if (output == null) return new FocusedAnswer(null, null, entries);
        return new FocusedAnswer(output.dishContext(), sceneAnswered ? output.scene() : null, entries);
    }

    private static Map<String, Object> buildFocusedResponseSchema() {
        Map<String, Object> entry = RecipeProcessOutputSchema.object(List.of(
                new Field("id", RecipeProcessOutputSchema.string(), true),
                new Field("focus", RecipeProcessOutputSchema.string(), true),
                new Field("state", RecipeProcessOutputSchema.string(), true),
                new Field("action", RecipeProcessOutputSchema.string(), true),
                new Field("sameAsPrevious", Map.of("type", "boolean"), true)));
        Map<String, Object> scene = RecipeProcessOutputSchema.object(List.of(
                new Field("vessel", RecipeProcessOutputSchema.string(), true),
                new Field("stove", RecipeProcessOutputSchema.string(), true),
                new Field("surface", RecipeProcessOutputSchema.string(), true),
                new Field("cuttingBoard", RecipeProcessOutputSchema.string(), true),
                new Field("prepBowl", RecipeProcessOutputSchema.string(), true),
                new Field("servingDish", RecipeProcessOutputSchema.string(), true)));
        return RecipeProcessOutputSchema.object(List.of(
                // Only asked for when the recipe has no equipment yet.
                new Field("dishContext", RecipeProcessOutputSchema.string(), false),
                new Field("scene", scene, false),
                new Field("prompts", RecipeProcessOutputSchema.arrayOf(entry), true)));
    }

    private int writeMissingPrompts(Long userId, Long recipeId, List<Long> scopeProcessIds) {
        List<VisualTarget> flows = new ArrayList<>();
        List<VisualTarget> steps = new ArrayList<>();
        String dishName = null;
        for (Long processId : scopeProcessIds) {
            VisualPreparation preparation = visualizationService.prepareVisualTargets(processId);
            Long owningRecipeId = preparation.process().getRecipeId();
            if (owningRecipeId != null && !owningRecipeId.equals(recipeId)) continue;
            boolean isPreparation = preparation.process().getType() == ProcessType.SUBPROCESS;
            (isPreparation ? flows : steps).addAll(preparation.targets());
            if (!isPreparation) dishName = preparation.process().getName();
        }
        // Read for every batch: an admin can switch the focused view on or off at runtime.
        if (visualizationService.focusedViewEnabled()) {
            return writeMissingFocusedPrompts(userId, recipeId, flows, steps, dishName);
        }

        // Preparations first, then MAIN in cooking order — the order the model reads the recipe in.
        List<BatchTarget> missing = new ArrayList<>();
        Map<String, String> keyById = new LinkedHashMap<>();
        List<String> styleReferences = new ArrayList<>();
        // An existing scene prompt is the strongest reference: it describes the very setting every image is drawn in.
        Optional<String> scenePrompt = visualizationService.findScene(recipeId)
                .map(com.processVisualisation.virtualKitchen.ai.asset.VisualizationAsset::getImagePrompt)
                .filter(StringUtils::hasText);
        scenePrompt.ifPresent(styleReferences::add);
        collect(recipeId, flows, "p", missing, keyById, styleReferences);
        collect(recipeId, steps, "m", missing, keyById, styleReferences);

        // The scene is written once per recipe, and only when some image still has to be drawn against it.
        boolean sceneNeeded = sceneAnchorService.isEnabled() && scenePrompt.isEmpty()
                && (!missing.isEmpty() || anyImageMissing(recipeId, flows) || anyImageMissing(recipeId, steps));
        if (missing.isEmpty() && !sceneNeeded) {
            log.debug("event=visualization_prompt_batch_skipped recipeId={} reason=no_missing_prompts", recipeId);
            return 0;
        }

        int written = 0;
        boolean sceneWritten = false;
        int start = 0;
        do {
            List<BatchTarget> chunk = missing.subList(start, Math.min(missing.size(), start + MAX_TARGETS_PER_REQUEST));
            List<String> references = styleReferences.subList(0, Math.min(MAX_STYLE_REFERENCES, styleReferences.size()));
            // Only the first request writes the scene; later chunks then see it among their style references.
            boolean askScene = sceneNeeded && start == 0;
            BatchAnswer answer = requestPrompts(userId, recipeId, chunk, List.copyOf(references), dishName, askScene);
            if (askScene && StringUtils.hasText(answer.scenePrompt())) {
                visualizationService.saveScenePrompt(recipeId, answer.scenePrompt().trim(), answer.dishContext());
                styleReferences.add(0, answer.scenePrompt().trim());
                sceneWritten = true;
            }
            for (BatchTarget target : chunk) {
                String prompt = answer.prompts().get(target.id());
                if (!StringUtils.hasText(prompt)) continue;
                visualizationService.savePrompt(keyById.get(target.id()), prompt.trim());
                styleReferences.add(prompt.trim());
                written++;
            }
            start += MAX_TARGETS_PER_REQUEST;
        } while (start < missing.size());
        log.info("event=visualization_prompt_batch_completed recipeId={} promptVersion={} targets={} written={} fallbackTargets={} sceneRequested={} sceneWritten={}",
                recipeId, RecipeProcessVisualizationPromptBuilder.BATCH_PROMPT_VERSION, missing.size(), written,
                missing.size() - written, sceneNeeded, sceneWritten);
        return written;
    }

    /** The node ids that start a group: the first step of each Preparation flow, and of each process's main steps. */
    static Set<String> firstOfGroup(List<VisualTarget> preparationTargets, List<VisualTarget> mainTargets) {
        Set<String> first = new java.util.HashSet<>();
        Set<String> seenFlows = new java.util.HashSet<>();
        for (VisualTarget target : preparationTargets) {
            String group = target.isPreparationStep() ? target.prepFlow().id() : target.nodeId();
            if (seenFlows.add(group)) first.add(target.nodeId());
        }
        if (!mainTargets.isEmpty()) first.add(mainTargets.get(0).nodeId());
        return first;
    }

    private boolean anyImageMissing(Long recipeId, List<VisualTarget> targets) {
        return targets.stream().anyMatch(target ->
                !visualizationService.hasImage(RecipeProcessVisualizationService.visualizationKey(recipeId, target)));
    }

    /** What one call answered: the scene fields (when asked for) and the prompts by id. */
    private record BatchAnswer(String dishContext, String scenePrompt, Map<String, String> prompts) {
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

    /** One provider call for a chunk; what it answered (empty when the answer couldn't be read). */
    private BatchAnswer requestPrompts(Long userId, Long recipeId, List<BatchTarget> chunk, List<String> references,
                                       String dishName, boolean askScene) {
        String userPrompt = promptBuilder.buildBatchUserPrompt(chunk, references, dishName, askScene);
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
                            .temperature(generationParams.getPromptBatch().getTemperature())
                            .maxTokens(generationParams.getPromptBatch().maxTokensFor(chunk.size()))
                            .responseFormat("json_object")
                            .responseSchema(responseSchema)
                            .responseSchemaName("visualization_prompts")
                            .operation("VISUALIZATION_PROMPT_BATCH")
                            .build();
                    long startNs = System.nanoTime();
                    AiResponse response = client.chat(request);
                    long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
                    return readPrompts(userPrompt, response, durationMs, chunk, askScene);
                }
        ).value();
    }

    private BatchAnswer readPrompts(String userPrompt, AiResponse response, long durationMs, List<BatchTarget> chunk, boolean askScene) {
        String content = response == null ? null : response.getContent();
        Map<String, String> prompts = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        BatchOutput output = null;
        if (!StringUtils.hasText(content)) {
            errors.add("AI response content is empty");
        } else {
            try {
                output = responseSupport.readValue(content, BatchOutput.class);
                List<String> ids = chunk.stream().map(BatchTarget::id).toList();
                for (BatchOutput.Entry entry : output.prompts() == null ? List.<BatchOutput.Entry>of() : output.prompts()) {
                    if (entry == null || !ids.contains(entry.id()) || !StringUtils.hasText(entry.imagePrompt())) continue;
                    prompts.putIfAbsent(entry.id(), entry.imagePrompt());
                }
                ids.stream().filter(id -> !prompts.containsKey(id))
                        .forEach(id -> errors.add("No prompt for " + id));
            } catch (Exception e) {
                output = null;
                errors.add("Invalid JSON format: " + e.getMessage());
            }
        }
        boolean sceneAnswered = askScene && output != null && StringUtils.hasText(output.scenePrompt());
        if (askScene && output != null && !sceneAnswered) errors.add("No scenePrompt");
        // A partial answer is still used; the targets it missed fall back to per-step prompts.
        responseSupport.persistAIResponse(CORRELATION_TYPE, userPrompt, response, !prompts.isEmpty() || sceneAnswered,
                durationMs, errors);
        if (output == null) return new BatchAnswer(null, null, prompts);
        return new BatchAnswer(output.dishContext(), sceneAnswered ? output.scenePrompt() : null, prompts);
    }

    private static Map<String, Object> buildResponseSchema() {
        Map<String, Object> entry = RecipeProcessOutputSchema.object(List.of(
                new Field("id", RecipeProcessOutputSchema.string(), true),
                new Field("imagePrompt", RecipeProcessOutputSchema.string(), true)));
        return RecipeProcessOutputSchema.object(List.of(
                new Field("styleGuide", RecipeProcessOutputSchema.string(), true),
                // Only asked for when the recipe has no scene yet.
                new Field("dishContext", RecipeProcessOutputSchema.string(), false),
                new Field("scenePrompt", RecipeProcessOutputSchema.string(), false),
                new Field("prompts", RecipeProcessOutputSchema.arrayOf(entry), true)));
    }
}
