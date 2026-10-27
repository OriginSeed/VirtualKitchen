package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAsset;
import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAssetRepository;
import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAssetType;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifact;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifactKeyBuilder;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifactService;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifactSpec;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.codec.GeneratedImageCodec;
import com.processVisualisation.virtualKitchen.ai.platform.dispatch.AiClientResolver;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestOutcome;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestQueueService;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.routing.ModelSelectionOutcome;
import com.processVisualisation.virtualKitchen.ai.provider.AiClient;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiRequest;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiResponse;
import com.processVisualisation.virtualKitchen.ai.recipe.RecipeProcessAiException;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider;
import com.processVisualisation.virtualKitchen.common.logging.FailureLogger;
import com.processVisualisation.virtualKitchen.common.persistence.SequenceGeneratorService;
import com.processVisualisation.virtualKitchen.recipe.model.PreparationFlows;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessType;
import com.processVisualisation.virtualKitchen.recipe.model.StepAnnotations;
import com.processVisualisation.virtualKitchen.recipe.repository.ProcessRepository;
import com.processVisualisation.virtualKitchen.recipe.service.ProcessStepOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Generates AI-driven image visualization assets for a Process model's own STEP nodes (CONDITION
 * nodes are never visualized): a {@link VisualizationAsset} lookup-fill-or-create lifecycle with
 * durable-artifact-before-upload sequencing via {@link AiArtifactService}/
 * {@link VisualizationImageArtifactConsumer}, reading each step's Action On (ingredients +
 * subprocess references). Scoped to one {@link Process} (a MAIN or a
 * SUBPROCESS) at a time: visualizing a process only visualizes its own steps, never a
 * subprocess referenced from one of them (see class javadoc on {@link Process} — a subprocess's
 * name already stands in for its summary/output, so no recursive walk is needed for V1).
 */
@Service
public class RecipeProcessVisualizationService {

    private static final Logger logger = LoggerFactory.getLogger(RecipeProcessVisualizationService.class);

    private final ProcessRepository processRepository;
    private final VisualizationAssetRepository assetRepository;
    private final SequenceGeneratorService sequenceGeneratorService;
    private final AiRequestQueueService queueService;
    private final AiClientResolver clientResolver;
    private final RecipeProcessVisualizationPromptBuilder promptBuilder;
    private final AiArtifactService artifactService;
    private final GeneratedImageCodec generatedImageCodec;
    private final VisualizationImageArtifactConsumer imageArtifactConsumer;
    private final RecipeStepVocabularyProvider vocabularyProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RecipeProcessVisualizationService(
            ProcessRepository processRepository,
            VisualizationAssetRepository assetRepository,
            SequenceGeneratorService sequenceGeneratorService,
            AiRequestQueueService queueService,
            AiClientResolver clientResolver,
            RecipeProcessVisualizationPromptBuilder promptBuilder,
            AiArtifactService artifactService,
            GeneratedImageCodec generatedImageCodec,
            VisualizationImageArtifactConsumer imageArtifactConsumer,
            RecipeStepVocabularyProvider vocabularyProvider
    ) {
        this.processRepository = processRepository;
        this.assetRepository = assetRepository;
        this.sequenceGeneratorService = sequenceGeneratorService;
        this.queueService = queueService;
        this.clientResolver = clientResolver;
        this.promptBuilder = promptBuilder;
        this.artifactService = artifactService;
        this.generatedImageCodec = generatedImageCodec;
        this.imageArtifactConsumer = imageArtifactConsumer;
        this.vocabularyProvider = vocabularyProvider;
    }

    /**
     * Loads the process and its ordered, STEP-only visualization inputs once, up front, so the
     * async job pipeline can fan the per-step work out across a {@code TaskPool} without every
     * task re-reading the process document itself.
     *
     * @param processId the process (MAIN or SUBPROCESS) to prepare
     * @throws RecipeProcessAiException if the process cannot be found
     */
    public ProcessStepPreparation prepareStepContexts(Long processId) {
        Process process = processRepository.findById(processId)
                .orElseThrow(() -> new RecipeProcessAiException("Process not found: " + processId));

        List<Process.ProcessNode> orderedSteps = ProcessStepOrder.orderedSteps(process);
        Map<String, String> preparedNameByKey = resolvePreparedNames(orderedSteps);

        List<StepContext> contexts = new ArrayList<>();
        String previousExpectedOutput = null;
        for (Process.ProcessNode node : orderedSteps) {
            Map<String, Object> data = node.getData() != null ? node.getData() : new LinkedHashMap<>();
            RecipeProcessVisualizationInput input = extractVisualizationInput(data, preparedNameByKey, previousExpectedOutput);
            contexts.add(new StepContext(node, input));
            previousExpectedOutput = input.expectedOutput();
        }

        return new ProcessStepPreparation(process, contexts);
    }

    /**
     * Resolves (finding, reusing, or generating) the {@link VisualizationAsset} for one step.
     * Touches only the {@code VisualizationAsset} collection and the AI/image/storage clients —
     * never the shared {@link Process} document — so it is safe to run concurrently across steps
     * of the same process.
     * <p>
     * The cache/dedup key stays the simple {@code recipeId::stepId} shape (V1 decision — no
     * cross-recipe/semantic reuse yet): two steps never share an asset, even across processes,
     * since {@code stepId} (a generated node id) is already globally unique.
     */
    public VisualizationAsset resolveVisualizationAsset(Long userId, Long recipeId, String stepId, RecipeProcessVisualizationInput input) {
        return resolveAsset(userId, VisualizationKeyBuilder.build(String.valueOf(recipeId), stepId),
                () -> promptBuilder.buildUserPrompt(input), null);
    }

    /**
     * Resolves the image for one visual target: a MAIN step's own image, or a preparation flow's single image
     * ({@link RecipeProcessVisualizationPromptBuilder#buildFlowUserPrompt}) keyed by its output step.
     */
    public VisualizationAsset resolveTarget(Long userId, Long recipeId, VisualTarget target) {
        return resolveTarget(userId, recipeId, target, null);
    }

    /**
     * Same as {@link #resolveTarget(Long, Long, VisualTarget)}, drawing a newly generated image against the recipe's
     * scene anchor (its dish context line, and its empty-scene image as a reference where the provider takes one).
     *
     * @param scene the recipe's scene anchor, or null to generate from the prompt alone
     */
    public VisualizationAsset resolveTarget(Long userId, Long recipeId, VisualTarget target, SceneReference scene) {
        Supplier<String> userPrompt = target.flow() == null
                ? () -> promptBuilder.buildUserPrompt(target.step())
                : () -> promptBuilder.buildFlowUserPrompt(target.flow().outputName(), target.flow().steps());
        return resolveAsset(userId, visualizationKey(recipeId, target), userPrompt, scene);
    }

    /**
     * A recipe's scene anchor, as step images use it.
     *
     * @param anchorKey identifies the anchor image ({@code assetId:imageUrl}); part of each step image's artifact key,
     *                  so an image drawn against an older anchor is never reused for a new one. Null without an image.
     * @param dishContext the one-line dish context sent with every step image, or null
     * @param image the empty-scene image sent as a reference, or null when there is none
     */
    public record SceneReference(String anchorKey, String dishContext, ImageGenerationClient.ReferenceImage image) {
    }

    /** The recipe's scene anchor asset, if one exists (it may still lack a prompt or an image). */
    public Optional<VisualizationAsset> findScene(Long recipeId) {
        return assetRepository.findByVisualizationKey(VisualizationKeyBuilder.buildScene(String.valueOf(recipeId)));
    }

    /**
     * Stores the scene anchor's prompt and dish context, as written by the prompt batch. A scene that already has a
     * prompt is left unchanged, so an anchor in use never shifts underneath the recipe's images.
     */
    public VisualizationAsset saveScenePrompt(Long recipeId, String scenePrompt, String dishContext) {
        String key = VisualizationKeyBuilder.buildScene(String.valueOf(recipeId));
        Optional<VisualizationAsset> existing = assetRepository.findByVisualizationKey(key);
        if (existing.isPresent() && StringUtils.hasText(existing.get().getImagePrompt())) {
            return existing.get();
        }
        VisualizationAsset scene = savePrompt(key, scenePrompt);
        scene.setDishContext(StringUtils.hasText(dishContext) ? dishContext.trim() : null);
        return assetRepository.save(scene);
    }

    /**
     * Generates the scene anchor's image from its prompt (no reference of its own) and stores it, like a step image.
     * Returns the asset with {@code imageUrl} set, or null when generation or upload failed.
     */
    public VisualizationAsset generateSceneImage(Long userId, VisualizationAsset scene) {
        VisualizationAsset generated = generateImage(userId, scene, null, SCENE_CORRELATION_TYPE);
        return StringUtils.hasText(generated.getImageUrl()) ? generated : null;
    }

    static final String SCENE_CORRELATION_TYPE = "process-visualization-scene";

    /** The asset key a visual target's image is stored under (see {@link #resolveTarget}). */
    public static String visualizationKey(Long recipeId, VisualTarget target) {
        return target.flow() == null
                ? VisualizationKeyBuilder.build(String.valueOf(recipeId), target.nodeId())
                : VisualizationKeyBuilder.buildFlow(String.valueOf(recipeId), target.nodeId());
    }

    /** Whether the asset under this key already has its image (so no image call is made for it). */
    public boolean hasImage(String visualizationKey) {
        return assetRepository.findByVisualizationKey(visualizationKey)
                .map(VisualizationAsset::getImageUrl)
                .filter(StringUtils::hasText)
                .isPresent();
    }

    /** The image prompt already stored for this key, or empty when it has none yet (it then needs one written). */
    public Optional<String> existingPrompt(String visualizationKey) {
        return assetRepository.findByVisualizationKey(visualizationKey)
                .map(VisualizationAsset::getImagePrompt)
                .filter(StringUtils::hasText);
    }

    /**
     * Stores an image prompt written ahead of the per-step work (by {@link VisualizationPromptBatchService}): fills
     * the existing asset's empty prompt, or creates the asset. {@link #resolveTarget} then finds the prompt and only
     * generates the image. An asset that already has a prompt is left unchanged.
     */
    public VisualizationAsset savePrompt(String visualizationKey, String imagePrompt) {
        Optional<VisualizationAsset> existing = assetRepository.findByVisualizationKey(visualizationKey);
        if (existing.isPresent() && StringUtils.hasText(existing.get().getImagePrompt())) {
            return existing.get();
        }
        VisualizationAsset asset = existing.orElseGet(() -> {
            VisualizationAsset created = new VisualizationAsset();
            created.setId(sequenceGeneratorService.generateSequence(VisualizationAsset.SEQUENCE_NAME));
            created.setVisualizationKey(visualizationKey);
            created.setType(VisualizationAssetType.ATOMIC);
            return created;
        });
        asset.setImagePrompt(imagePrompt);
        asset.setVideoPrompt(null);
        asset.setVideoUrl(null);
        return assetRepository.save(asset);
    }

    private VisualizationAsset resolveAsset(Long userId, String visualizationKey, Supplier<String> userPrompt, SceneReference scene) {
        Optional<VisualizationAsset> existingAsset = assetRepository.findByVisualizationKey(visualizationKey);
        VisualizationAsset asset;
        if (existingAsset.isPresent()) {
            asset = existingAsset.get();
            if (asset.getImagePrompt() == null || asset.getImagePrompt().isEmpty()) {
                asset = generatePrompt(userId, visualizationKey, userPrompt);
            }
            requirePrompted(asset, visualizationKey);
            if (asset.getImageUrl() == null || asset.getImageUrl().isEmpty()) {
                asset = generateImage(userId, asset, scene, STEP_IMAGE_CORRELATION_TYPE);
            }
        } else {
            asset = generatePrompt(userId, visualizationKey, userPrompt);
            requirePrompted(asset, visualizationKey);
            asset = generateImage(userId, asset, scene, STEP_IMAGE_CORRELATION_TYPE);
        }
        return asset;
    }

    private static final String STEP_IMAGE_CORRELATION_TYPE = "process-visualization-image";

    /**
     * Attaches each successfully generated asset to its STEP node in the stored process. Each step
     * is written with its own atomic in-place update ({@link ProcessRepository#setStepVisualization})
     * rather than a read-modify-save of the whole document, so the (minutes-long) job never
     * overwrites edits the Recipe Tool autosaved meanwhile, and a process or step deleted meanwhile
     * is simply skipped instead of being re-created. Steps missing from {@code assetsByStepId}
     * (failed generations) are left untouched and can be retried later.
     */
    public void attachResultsAndSave(Process process, Map<String, VisualizationAsset> assetsByStepId) {
        assetsByStepId.forEach((stepId, asset) -> processRepository.setStepVisualization(
                process.getId(), stepId, asset.getId(), asset.getImagePrompt(), asset.getImageUrl()));
    }

    /**
     * What a visualization job generates for one process: one image per MAIN step, but ONE image per Preparation
     * flow (attached to the step that makes the flow's output), never one per preparation step.
     */
    public VisualPreparation prepareVisualTargets(Long processId) {
        ProcessStepPreparation prepared = prepareStepContexts(processId);
        Process process = prepared.process();
        if (process.getType() != ProcessType.SUBPROCESS) {
            return new VisualPreparation(process, prepared.steps().stream()
                    .map(context -> new VisualTarget(context.node().getId(), context.input(), null)).toList());
        }
        List<Process.ProcessNode> orderedSteps = ProcessStepOrder.orderedSteps(process);
        Map<String, String> preparedNameByKey = resolvePreparedNames(orderedSteps);
        List<VisualTarget> targets = new ArrayList<>();
        for (PreparationFlows.Flow flow : PreparationFlows.flowsOf(process)) {
            if (flow.output() == null) continue;
            Set<String> nodeIds = new HashSet<>();
            flow.nodes().forEach(node -> nodeIds.add(node.getId()));
            // Continuity stays inside the flow: independent flows don't continue from one another.
            List<RecipeProcessVisualizationInput> inputs = new ArrayList<>();
            String previousExpectedOutput = null;
            for (Process.ProcessNode node : orderedSteps) {
                if (!nodeIds.contains(node.getId())) continue;
                Map<String, Object> data = node.getData() != null ? node.getData() : new LinkedHashMap<>();
                RecipeProcessVisualizationInput input = extractVisualizationInput(data, preparedNameByKey, previousExpectedOutput);
                inputs.add(input);
                previousExpectedOutput = input.expectedOutput();
            }
            if (inputs.isEmpty()) continue;
            targets.add(new VisualTarget(flow.output().getId(), null, new FlowContext(flow.outputName(), inputs)));
        }
        return new VisualPreparation(process, targets);
    }

    /**
     * The node ids a visualization job attaches images to, without loading inputs: every STEP of a MAIN process,
     * the output step of every Preparation flow (see {@link #prepareVisualTargets}).
     */
    public static List<String> visualTargetNodeIds(Process process) {
        if (process.getType() != ProcessType.SUBPROCESS) {
            return ProcessStepOrder.orderedSteps(process).stream().map(Process.ProcessNode::getId).toList();
        }
        return PreparationFlows.flowsOf(process).stream()
                .filter(flow -> flow.output() != null)
                .map(flow -> flow.output().getId())
                .toList();
    }

    /** One image to generate: a MAIN step ({@code step}) or a whole Preparation flow ({@code flow}), attached to {@code nodeId}. */
    public record VisualTarget(String nodeId, RecipeProcessVisualizationInput step, FlowContext flow) {
    }

    /** A Preparation flow's single image: its named output and its steps in cooking order. */
    public record FlowContext(String outputName, List<RecipeProcessVisualizationInput> steps) {
    }

    /** The result of {@link #prepareVisualTargets}: the loaded process with what to generate for it. */
    public record VisualPreparation(Process process, List<VisualTarget> targets) {
    }

    /** One STEP's pre-loaded generation context: its process node and extracted visualization input. */
    public record StepContext(Process.ProcessNode node, RecipeProcessVisualizationInput input) {
    }

    /** The result of {@link #prepareStepContexts}: the loaded process with its ordered step contexts. */
    public record ProcessStepPreparation(Process process, List<StepContext> steps) {
    }

    private VisualizationAsset generatePrompt(Long userId, String visualizationKey, Supplier<String> userPrompt) {
        try {
            AiRequestOutcome<String> outcome = queueService.executeInline(
                    userId,
                    AiCapability.TEXT_TO_TEXT,
                    null,
                    null,
                    "process-visualization-prompt",
                    visualizationKey,
                    selection -> {
                        AiClient client = clientResolver.resolveTextClient(selection.model());
                        AiRequest request = AiRequest.builder()
                                .model(selection.model().getProviderModelId())
                                .systemPrompt(promptBuilder.buildSystemPrompt())
                                .userPrompt(userPrompt.get())
                                .temperature(0.4d)
                                .maxTokens(2000)
                                .operation("VISUALIZATION_PROMPT")
                                .build();
                        AiResponse response = client.chat(request);
                        return parseImagePrompt(response == null ? null : response.getContent());
                    }
            );

            VisualizationAsset asset = new VisualizationAsset();
            asset.setId(sequenceGeneratorService.generateSequence(VisualizationAsset.SEQUENCE_NAME));
            asset.setVisualizationKey(visualizationKey);
            asset.setType(VisualizationAssetType.ATOMIC);
            asset.setImagePrompt(outcome.value());
            asset.setVideoPrompt(null);
            asset.setVideoUrl(null);
            return assetRepository.save(asset);
        } catch (Exception e) {
            FailureLogger.logFailure(logger, "visualization_prompt_failed", e, "visualizationKey=" + visualizationKey);
        }
        return null;
    }

    /**
     * Generates this step's image and places it in object storage, in two separable stages so a
     * storage failure cannot destroy a paid generation — the provider's bytes are staged as an
     * {@code AiArtifact} (inside {@code executeInline}) before the upload runs, and the artifact is
     * only retired once the upload succeeds, so a retry reuses the already-generated image instead
     * of paying for a new one. Reuses {@link VisualizationImageArtifactConsumer} unchanged, since it
     * only depends on the (generic) {@code visualizationKey}, not on which model produced the asset.
     */
    private VisualizationAsset generateImage(Long userId, VisualizationAsset asset, SceneReference scene, String correlationType) {
        String visualizationKey = asset.getVisualizationKey();
        String dishContext = scene == null ? null : scene.dishContext();
        ImageGenerationClient.ReferenceImage reference = scene == null ? null : scene.image();
        try {
            // Without a scene the key is exactly as before, so payloads retained before scene anchors stay reusable.
            String[] keyInputs = scene == null
                    ? new String[]{asset.getImagePrompt()}
                    : new String[]{asset.getImagePrompt(), orBlank(dishContext), orBlank(scene.anchorKey())};
            AiArtifactSpec<ImageGenerationClient.GeneratedImage> artifactSpec = AiArtifactSpec.of(
                    AiArtifactKeyBuilder.build(
                            AiCapability.TEXT_TO_IMAGE,
                            correlationType,
                            visualizationKey,
                            keyInputs),
                    generatedImageCodec,
                    VisualizationImageArtifactConsumer.CONSUMER_ID
            );

            AiRequestOutcome<ImageGenerationClient.GeneratedImage> outcome = queueService.executeInline(
                    userId,
                    AiCapability.TEXT_TO_IMAGE,
                    null,
                    null,
                    correlationType,
                    visualizationKey,
                    artifactSpec,
                    selection -> {
                        ImageGenerationClient client = clientResolver.resolveImageClient(selection.model());
                        // Only a provider that really sends the reference is told about it.
                        boolean withReference = reference != null && client.supportsReferenceImages();
                        String text = promptBuilder.buildImageRequestText(asset.getImagePrompt(), dishContext, withReference);
                        return client.generate(text, withReference ? List.of(reference) : List.of());
                    }
            );

            // The payload is durable from here on, whether it was just generated or reused.
            AiArtifact artifact = artifactOrTransient(outcome, visualizationKey, correlationType);
            String imageUrl = imageArtifactConsumer.consume(artifact, outcome.value());
            artifactService.markConsumed(artifact.getId());

            ModelSelectionOutcome selection = outcome.selection();
            asset.setImageUrl(imageUrl);
            asset.setImageFailureReason(null);
            asset.setResolvedModelKey(selection.model().getKey());
            asset.setResolvedTier(selection.model().getTier());
            asset.setUsedFallback(selection.usedFallback());
        } catch (Exception e) {
            asset.setImageUrl(null);
            asset.setImageFailureReason(describeFailure(e));
            // Any generated payload is retained in the artifact store for recovery.
            FailureLogger.logFailure(logger, "visualization_image_failed", e,
                    "visualizationKey=" + visualizationKey + " payloadRetained=true");
        }
        asset.setVideoUrl(null);
        return assetRepository.save(asset);
    }

    private AiArtifact artifactOrTransient(
            AiRequestOutcome<ImageGenerationClient.GeneratedImage> outcome, String visualizationKey, String correlationType) {
        if (outcome.artifact() != null) {
            return outcome.artifact();
        }
        ModelSelectionOutcome selection = outcome.selection();
        AiArtifact transientArtifact = new AiArtifact();
        transientArtifact.setCorrelationId(visualizationKey);
        transientArtifact.setCorrelationType(correlationType);
        transientArtifact.setCapability(AiCapability.TEXT_TO_IMAGE);
        transientArtifact.setProducedByModelKey(selection.model().getKey());
        transientArtifact.setProducedByTier(selection.model().getTier());
        transientArtifact.setUsedFallback(selection.usedFallback());
        return transientArtifact;
    }

    private String describeFailure(Exception e) {
        String message = e.getMessage();
        return StringUtils.hasText(message)
                ? e.getClass().getSimpleName() + ": " + message
                : e.getClass().getSimpleName();
    }

    private void requirePrompted(VisualizationAsset asset, String visualizationKey) {
        if (asset == null) {
            throw new RecipeProcessAiException(
                    "Could not generate a visualization prompt for step: " + visualizationKey);
        }
    }

    private String parseImagePrompt(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(stripCodeFences(content));
            return root.path("imagePrompt").asText("");
        } catch (Exception ex) {
            logger.warn("event=visualization_prompt_unparsed fallback=raw_content contentChars={} errorType={}",
                    content.length(), ex.getClass().getSimpleName());
            return content.trim();
        }
    }

    private String stripCodeFences(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewLine = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewLine >= 0 && lastFence > firstNewLine) {
                return trimmed.substring(firstNewLine + 1, lastFence).trim();
            }
        }
        return content;
    }

    /** A step's use of a prepared component: a Preparation step's output ({@code stepId}), or (older data) a whole process. */
    private record PreparedRef(long processId, String stepId) {
        String key() {
            return stepId == null ? String.valueOf(processId) : processId + ":" + stepId;
        }
    }

    /**
     * Batch-resolves the name of every prepared component used by any step's Action On, in one query: a Preparation
     * step's Expected Output (falling back to the process's name), or a whole process's name.
     */
    private Map<String, String> resolvePreparedNames(List<Process.ProcessNode> stepNodes) {
        List<PreparedRef> references = new ArrayList<>();
        for (Process.ProcessNode node : stepNodes) {
            Map<String, Object> data = node.getData() != null ? node.getData() : Map.of();
            references.addAll(extractPreparedRefs(extractStepFields(data)));
        }
        if (references.isEmpty()) {
            return Map.of();
        }
        Set<Long> processIds = new LinkedHashSet<>();
        references.forEach(reference -> processIds.add(reference.processId()));
        Map<Long, Process> processesById = processRepository.findAllById(processIds).stream()
                .collect(Collectors.toMap(Process::getId, process -> process, (a, b) -> a));

        Map<String, String> names = new LinkedHashMap<>();
        for (PreparedRef reference : references) {
            Process process = processesById.get(reference.processId());
            if (process == null) continue;
            String name = process.getName();
            if (reference.stepId() != null && process.getNodes() != null) {
                for (Process.ProcessNode node : process.getNodes()) {
                    if (node == null || !reference.stepId().equals(node.getId())) continue;
                    String output = asString(extractStepFields(node.getData() != null ? node.getData() : Map.of()).get("expectedOutput")).trim();
                    if (!output.isEmpty()) name = output;
                }
            }
            names.put(reference.key(), name);
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractStepFields(Map<String, Object> data) {
        Object nested = data.get("step");
        return nested instanceof Map ? (Map<String, Object>) nested : data;
    }

    private RecipeProcessVisualizationInput extractVisualizationInput(
            Map<String, Object> data, Map<String, String> preparedNameByKey, String previousExpectedOutput) {
        Map<String, Object> step = extractStepFields(data);

        String actionId = asString(step.get("action"));
        String action = "custom".equals(actionId)
                ? orBlank(asString(step.get("customActionName")))
                : vocabularyProvider.actionLabel(actionId);

        List<RecipeProcessVisualizationInput.IngredientTarget> ingredients = extractIngredients(step);
        List<String> subprocessNames = extractPreparedRefs(step).stream()
                .map(reference -> preparedNameByKey.getOrDefault(reference.key(), "a prepared component"))
                .toList();

        String flameLevelId = asString(step.get("flameLevelId"));
        String flameLevel = "custom".equals(flameLevelId)
                ? asString(step.get("customFlameLevel"))
                : (StringUtils.hasText(flameLevelId) ? vocabularyProvider.flameLevelLabel(flameLevelId) : "");

        String durationValue = asString(step.get("durationValue"));
        String durationUnit = asString(step.get("durationUnit"));
        String duration = StringUtils.hasText(durationValue) && StringUtils.hasText(durationUnit)
                ? durationValue + " " + durationUnit
                : "";

        return new RecipeProcessVisualizationInput(
                action,
                ingredients,
                subprocessNames,
                // Annotations read as names; a prepared component's name is its live Expected Output.
                StepAnnotations.toPlainText(asString(step.get("actionDescription")), annotation ->
                        StepAnnotations.PREPARED.equals(annotation.kind()) ? preparedNameByKey.get(annotation.id()) : null),
                asString(step.get("expectedOutput")),
                temperatureLabel(step),
                flameLevel,
                duration,
                previousExpectedOutput
        );
    }

    @SuppressWarnings("unchecked")
    private List<RecipeProcessVisualizationInput.IngredientTarget> extractIngredients(Map<String, Object> step) {
        Object actionOn = step.get("actionOn");
        if (!(actionOn instanceof Map)) return List.of();
        Object rawIngredients = ((Map<String, Object>) actionOn).get("ingredients");
        if (!(rawIngredients instanceof List)) return List.of();

        List<RecipeProcessVisualizationInput.IngredientTarget> targets = new ArrayList<>();
        for (Object entry : (List<Object>) rawIngredients) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> ingredient = (Map<String, Object>) entry;

            String ingredientId = asString(ingredient.get("ingredientId"));
            if (!StringUtils.hasText(ingredientId)) continue;
            String customName = asString(ingredient.get("customIngredientName"));
            String name = "custom".equals(ingredientId) && StringUtils.hasText(customName)
                    ? customName
                    : vocabularyProvider.ingredientLabel(ingredientId);

            String quantity = formatQuantity(ingredient.get("quantity"));
            String unitId = asString(ingredient.get("unit"));
            String unit = "custom".equals(unitId) && StringUtils.hasText(asString(ingredient.get("customUnit")))
                    ? asString(ingredient.get("customUnit")).trim()
                    : vocabularyProvider.unitLabel(unitId);

            String preparationStyleId = asString(ingredient.get("preparationStyleId"));
            String preparationStyle = "custom".equals(preparationStyleId)
                    ? asString(ingredient.get("customPreparationStyle"))
                    : (StringUtils.hasText(preparationStyleId) ? vocabularyProvider.preparationStyleLabel(preparationStyleId) : "");

            targets.add(new RecipeProcessVisualizationInput.IngredientTarget(name, quantity, unit, preparationStyle));
        }
        return targets;
    }

    @SuppressWarnings("unchecked")
    private List<PreparedRef> extractPreparedRefs(Map<String, Object> step) {
        Object actionOn = step.get("actionOn");
        if (!(actionOn instanceof Map)) return List.of();
        Object rawProcesses = ((Map<String, Object>) actionOn).get("processes");
        if (!(rawProcesses instanceof List)) return List.of();

        List<PreparedRef> references = new ArrayList<>();
        for (Object entry : (List<Object>) rawProcesses) {
            if (!(entry instanceof Map)) continue;
            Object processId = ((Map<String, Object>) entry).get("processId");
            if (processId instanceof Number number) {
                String stepId = asString(((Map<String, Object>) entry).get("stepId")).trim();
                references.add(new PreparedRef(number.longValue(), stepId.isEmpty() ? null : stepId));
            }
        }
        return references;
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String orBlank(String value) {
        return value == null ? "" : value;
    }

    /** "180 °C" from temperatureValue/temperatureUnit, else the legacy free-text {@code temperature} field. */
    private String temperatureLabel(Map<String, Object> step) {
        String value = asString(step.get("temperatureValue")).trim();
        if (!StringUtils.hasText(value)) return asString(step.get("temperature"));
        String unit = asString(step.get("temperatureUnit"));
        return StringUtils.hasText(unit) ? value + " °" + unit : value;
    }

    private String formatQuantity(Object value) {
        if (!(value instanceof Number number)) return "";
        double doubleValue = number.doubleValue();
        return doubleValue == Math.floor(doubleValue) && !Double.isInfinite(doubleValue)
                ? String.valueOf((long) doubleValue)
                : String.valueOf(doubleValue);
    }
}
