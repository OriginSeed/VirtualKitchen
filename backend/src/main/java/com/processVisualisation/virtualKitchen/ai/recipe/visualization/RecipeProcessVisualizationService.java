package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import com.processVisualisation.virtualKitchen.common.logging.UsageLedger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.platform.config.AiGenerationParamsProperties;
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
import com.processVisualisation.virtualKitchen.ai.provider.ImageStorageClient;
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
import org.springframework.beans.factory.annotation.Autowired;
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
    private final VisualizationImageProfiles imageProfiles;
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
            RecipeStepVocabularyProvider vocabularyProvider,
            VisualizationImageProfiles imageProfiles
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
        this.imageProfiles = imageProfiles;
    }

    /** Sampling parameters; admin runtime overrides apply to the next request. Defaults when not injected (tests). */
    private AiGenerationParamsProperties generationParams = new AiGenerationParamsProperties();

    @Autowired
    void setGenerationParams(AiGenerationParamsProperties generationParams) {
        this.generationParams = generationParams;
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
                () -> promptBuilder.buildUserPrompt(input), null, null, null,
                imageProfiles.settingsFor(input == null ? null : input.actionId()));
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
        return resolveTarget(userId, recipeId, target, scene, null);
    }

    /**
     * Same as {@link #resolveTarget(Long, Long, VisualTarget, SceneReference)}, also drawing a focused image against
     * the previous image of its chain where the provider takes reference images.
     *
     * @param previous the previous step's image, or null when this image starts a chain or stands alone
     */
    public VisualizationAsset resolveTarget(Long userId, Long recipeId, VisualTarget target, SceneReference scene,
                                            PreviousStep previous) {
        // A prompt this step has to write for itself is a focused one only while the focused view is on.
        VisualFocus fallbackFocus = focusedViewEnabled() ? defaultFocus(target) : null;
        Supplier<String> userPrompt;
        if (target.flow() == null) {
            userPrompt = fallbackFocus == null
                    ? () -> promptBuilder.buildUserPrompt(target.step())
                    : () -> promptBuilder.buildFocusedUserPrompt(target.step());
        } else {
            userPrompt = fallbackFocus == null
                    ? () -> promptBuilder.buildFlowUserPrompt(target.flow().outputName(), target.flow().steps())
                    : () -> promptBuilder.buildFocusedFlowUserPrompt(target.flow().outputName(), target.flow().steps());
        }
        return resolveAsset(userId, visualizationKey(recipeId, target), userPrompt, fallbackFocus, scene, previous,
                imageProfiles.settingsFor(profileActionId(target)));
    }

    /** Whether new prompts are written for the Focused Cooking View (an admin can switch it off at runtime). */
    public boolean focusedViewEnabled() {
        return imageProfiles.focusedViewEnabled();
    }

    /** Whether focused main steps are drawn against the previous image of the same focus. */
    public boolean referenceChainEnabled() {
        return imageProfiles.referenceChainEnabled();
    }

    /**
     * What a focused image of this target centres on unless the prompt writer says otherwise: a step's action
     * decides; a multi-step preparation is a storyboard.
     */
    public VisualFocus defaultFocus(VisualTarget target) {
        // Only a whole flow (focused view off) is a storyboard; a Preparation step is framed like any other step.
        if (target.flow() != null && target.flow().steps() != null && target.flow().steps().size() > 1) {
            return VisualFocus.STORYBOARD;
        }
        return imageProfiles.focusFor(profileActionId(target));
    }

    /**
     * The focus a main step's image continues its chain with: the focus stored with its prompt, or (when the step
     * will still write its own prompt) its default focus. Empty for preparation flows, for prompts written before
     * the focused view, and whenever the focused view is off for a step without a prompt.
     */
    public Optional<VisualFocus> chainFocus(Long recipeId, VisualTarget target) {
        // Preparation steps continue only within their own flow (see the job's preparation runner).
        if (target.flow() != null || target.isPreparationStep()) {
            return Optional.empty();
        }
        Optional<VisualizationAsset> asset = assetRepository.findByVisualizationKey(visualizationKey(recipeId, target))
                .filter(found -> StringUtils.hasText(found.getImagePrompt()));
        VisualFocus focus = asset.isPresent()
                ? asset.get().getFocus()
                : (focusedViewEnabled() ? defaultFocus(target) : null);
        return Optional.ofNullable(focus).filter(VisualFocus::chains);
    }

    /**
     * The previous image of a chain, as the next step's reference.
     *
     * @param key identifies the image ({@code assetId:imageUrl}); part of the next image's artifact key
     */
    public record PreviousStep(String key, ImageGenerationClient.ReferenceImage image) {
    }

    /**
     * The action that picks a target's image profile: a step's own action; for a preparation flow, its last step's
     * action, since the flow's image is attached to (and ends at) that output step.
     */
    static String profileActionId(VisualTarget target) {
        if (target.flow() == null) {
            return target.step() == null ? null : target.step().actionId();
        }
        List<RecipeProcessVisualizationInput> steps = target.flow().steps();
        return steps == null || steps.isEmpty() ? null : steps.get(steps.size() - 1).actionId();
    }

    /**
     * A recipe's scene anchor, as step images use it.
     *
     * @param anchorKey identifies the anchor image ({@code assetId:imageUrl}); part of each step image's artifact key,
     *                  so an image drawn against an older anchor is never reused for a new one. Null without an image.
     * @param dishContext the one-line dish context sent with every step image, or null
     * @param image the empty-scene image sent as a reference, or null when there is none
     * @param sceneContext the recipe's equipment for focused images, or null to use the configured defaults
     */
    public record SceneReference(String anchorKey, String dishContext, ImageGenerationClient.ReferenceImage image,
                                 SceneContext sceneContext) {

        /** A scene written before the focused view: no equipment of its own. */
        public SceneReference(String anchorKey, String dishContext, ImageGenerationClient.ReferenceImage image) {
            this(anchorKey, dishContext, image, null);
        }
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
     * Stores the recipe's equipment for focused images, with its dish context. A recipe without a scene gets one
     * whose prompt is the empty focused setup ({@link RecipeProcessVisualizationPromptBuilder#buildFocusedScenePrompt});
     * an existing scene keeps its prompt and image and only gains what it lacks.
     */
    public VisualizationAsset saveSceneContext(Long recipeId, SceneContext sceneContext, String dishContext) {
        VisualizationImageProperties.Scene style = imageProfiles.scene();
        SceneContext equipment = SceneContext.orDefaults(sceneContext, style.equipment());
        VisualizationAsset scene = saveScenePrompt(recipeId,
                promptBuilder.buildFocusedScenePrompt(equipment, style), dishContext);
        boolean changed = false;
        if (scene.getSceneContext() == null) {
            scene.setSceneContext(equipment);
            changed = true;
        }
        if (!StringUtils.hasText(scene.getDishContext()) && StringUtils.hasText(dishContext)) {
            scene.setDishContext(dishContext.trim());
            changed = true;
        }
        return changed ? assetRepository.save(scene) : scene;
    }

    /**
     * Generates the scene anchor's image from its prompt (no reference of its own) and stores it, like a step image.
     * Returns the asset with {@code imageUrl} set, or null when generation or upload failed.
     */
    public VisualizationAsset generateSceneImage(Long userId, VisualizationAsset scene) {
        VisualizationAsset generated = generateImage(userId, scene, null, null, SCENE_CORRELATION_TYPE,
                ImageGenerationClient.ImageGenerationSettings.NONE);
        return StringUtils.hasText(generated.getImageUrl()) ? generated : null;
    }

    static final String SCENE_CORRELATION_TYPE = "process-visualization-scene";

    /** The asset key a visual target's image is stored under (see {@link #resolveTarget}). */
    public static String visualizationKey(Long recipeId, VisualTarget target) {
        return target.flow() == null
                ? VisualizationKeyBuilder.build(String.valueOf(recipeId), target.nodeId())
                : VisualizationKeyBuilder.buildFlow(String.valueOf(recipeId), target.nodeId());
    }

    /**
     * "Regenerate and replace": forgets the stored prompt and image of every target, and of the shared panel images
     * any of them was cut from, so the next run writes new prompts (from the steps as they are now) and draws new
     * images instead of reusing them. The recipe's scene anchor is kept, so new images stay consistent with the rest
     * of the recipe. The process nodes keep their current image until the new one is attached.
     *
     * @return how many assets were cleared
     */
    public int clearForRegeneration(Long recipeId, List<VisualTarget> targets) {
        List<String> keys = targets.stream().map(target -> visualizationKey(recipeId, target)).toList();
        List<VisualizationAsset> cleared = new ArrayList<>(assetRepository.findByVisualizationKeyIn(keys));

        Set<String> stepIds = new HashSet<>();
        targets.forEach(target -> stepIds.add(target.nodeId()));
        String gridPrefix = VisualizationKeyBuilder.buildGrid(String.valueOf(recipeId), List.of());
        for (VisualizationAsset grid : assetRepository.findByVisualizationKeyStartingWith(gridPrefix)) {
            String panels = grid.getVisualizationKey().substring(gridPrefix.length());
            if (Arrays.stream(panels.split("\\+")).anyMatch(stepIds::contains)) {
                cleared.add(grid);
            }
        }

        for (VisualizationAsset asset : cleared) {
            asset.setImagePrompt(null);
            asset.setImageUrl(null);
            asset.setImageFailureReason(null);
            asset.setReusesPrevious(null);
            asset.setSourceGridKey(null);
        }
        assetRepository.saveAll(cleared);
        return cleared.size();
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
        return savePrompt(visualizationKey, imagePrompt, null);
    }

    /**
     * Same as {@link #savePrompt(String, String)} for a focused image: {@code imagePrompt} is the step's own text and
     * {@code focus} what the image centres on. A null focus stores a self-contained prompt, as before.
     */
    public VisualizationAsset savePrompt(String visualizationKey, String imagePrompt, VisualFocus focus) {
        return savePrompt(visualizationKey, imagePrompt, focus, null);
    }

    /**
     * Same as {@link #savePrompt(String, String, VisualFocus)}, also storing whether the step looks the same as the
     * step before it ({@link VisualizationAsset#getReusesPrevious()}); null leaves it unknown.
     */
    public VisualizationAsset savePrompt(String visualizationKey, String imagePrompt, VisualFocus focus,
                                         Boolean reusesPrevious) {
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
        asset.setFocus(focus);
        asset.setReusesPrevious(reusesPrevious);
        asset.setVideoPrompt(null);
        asset.setVideoUrl(null);
        return assetRepository.save(asset);
    }

    /**
     * @param fallbackFocus the focus of a prompt this call has to write itself, or null to write a self-contained one
     */
    private VisualizationAsset resolveAsset(Long userId, String visualizationKey, Supplier<String> userPrompt,
                                            VisualFocus fallbackFocus, SceneReference scene, PreviousStep previous,
                                            ImageGenerationClient.ImageGenerationSettings settings) {
        Optional<VisualizationAsset> existingAsset = assetRepository.findByVisualizationKey(visualizationKey);
        VisualizationAsset asset;
        if (existingAsset.isPresent()) {
            asset = existingAsset.get();
            if (asset.getImagePrompt() == null || asset.getImagePrompt().isEmpty()) {
                asset = generatePrompt(userId, visualizationKey, userPrompt, fallbackFocus);
            }
            requirePrompted(asset, visualizationKey);
            if (asset.getImageUrl() == null || asset.getImageUrl().isEmpty()) {
                asset = generateImage(userId, asset, scene, previous, STEP_IMAGE_CORRELATION_TYPE, settings);
            }
        } else {
            asset = generatePrompt(userId, visualizationKey, userPrompt, fallbackFocus);
            requirePrompted(asset, visualizationKey);
            asset = generateImage(userId, asset, scene, previous, STEP_IMAGE_CORRELATION_TYPE, settings);
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

    // ---------------------------------------------------------------------------------------------------------
    // Image cost: Preparation steps drawn as panels of one shared image, and look-alike steps reusing an image.
    // ---------------------------------------------------------------------------------------------------------

    /** The side of a shared panel image, for providers that take a size (Gemini ignores it). */
    static final int GRID_SIZE = 1024;

    /** Reads a stored panel image back when its steps still need cutting; optional so tests can leave it out. */
    private ImageStorageClient imageStorageClient;

    @Autowired(required = false)
    void setImageStorageClient(ImageStorageClient imageStorageClient) {
        this.imageStorageClient = imageStorageClient;
    }

    /** How many Preparation steps share one generated image (1, 2 or 4); read for every job. */
    public int preparationStepsPerImage() {
        return imageProfiles.preparationStepsPerImage();
    }

    /** Whether steps marked as looking like the previous one reuse its image; read for every job. */
    public boolean skipLookAlikeSteps() {
        return imageProfiles.skipLookAlikeSteps();
    }

    /**
     * How a run of {@code steps} Preparation steps is split into images of at most {@code perImage} steps: groups of
     * 4, 2 or 1 in order, never larger than {@code perImage} (7 steps at 4 per image: 4, 2, 1).
     */
    public static List<Integer> chunkSizes(int steps, int perImage) {
        List<Integer> sizes = new ArrayList<>();
        int left = steps;
        while (left > 0) {
            int size = perImage >= 4 && left >= 4 ? 4 : perImage >= 2 && left >= 2 ? 2 : 1;
            sizes.add(size);
            left -= size;
        }
        return sizes;
    }

    /** Whether this step, still without an image, was marked by the prompt writer as looking like the step before. */
    public boolean marksLookAlike(Long recipeId, VisualTarget target) {
        return assetRepository.findByVisualizationKey(visualizationKey(recipeId, target))
                .filter(asset -> !StringUtils.hasText(asset.getImageUrl()))
                .map(asset -> Boolean.TRUE.equals(asset.getReusesPrevious()))
                .orElse(false);
    }

    /**
     * Gives a look-alike step the image of the step before it, without an image call. Returns the step's asset with
     * {@code imageUrl} set.
     */
    public VisualizationAsset reuseImage(Long recipeId, VisualTarget target, VisualizationAsset source) {
        String key = visualizationKey(recipeId, target);
        VisualizationAsset asset = assetRepository.findByVisualizationKey(key)
                .orElseThrow(() -> new RecipeProcessAiException("No visualization asset for key " + key));
        asset.setImageUrl(source.getImageUrl());
        asset.setImageFailureReason(null);
        asset.setResolvedModelKey(source.getResolvedModelKey());
        asset.setResolvedTier(source.getResolvedTier());
        asset.setUsedFallback(source.isUsedFallback());
        asset.setSourceGridKey(source.getSourceGridKey());
        UsageLedger.increment(UsageLedger.Counter.ARTIFACTS_REUSED);
        logger.debug("event=visualization_image_reused visualizationKey={} sourceKey={}", key, source.getVisualizationKey());
        return assetRepository.save(asset);
    }

    /** The step's asset with its focused prompt, writing the prompt when the batch left it without one. */
    private VisualizationAsset ensurePrompted(Long userId, Long recipeId, VisualTarget target) {
        String key = visualizationKey(recipeId, target);
        Optional<VisualizationAsset> existing = assetRepository.findByVisualizationKey(key);
        if (existing.isPresent() && StringUtils.hasText(existing.get().getImagePrompt())) {
            return existing.get();
        }
        VisualizationAsset asset = generatePrompt(userId, key, () -> promptBuilder.buildFocusedUserPrompt(target.step()),
                defaultFocus(target));
        requirePrompted(asset, key);
        return asset;
    }

    /**
     * Draws 2 or 4 Preparation steps as equal panels of ONE image (stored as its own asset, see
     * {@link VisualizationKeyBuilder#buildGrid}), then cuts it into each step's own image. A panel image that already
     * exists is only cut again, never paid for twice. Returns the steps' assets in order; a step without
     * {@code imageUrl} carries the failure reason.
     *
     * @param previous the image before these steps in their preparation, or null
     */
    public List<VisualizationAsset> resolveGrid(Long userId, Long recipeId, List<VisualTarget> targets,
                                                SceneReference scene, PreviousStep previous) {
        if (!VisualizationGridSlicer.isPanelCount(targets.size())) {
            throw new IllegalArgumentException("A panel image has 2 or 4 steps, not " + targets.size());
        }
        List<VisualizationAsset> steps = new ArrayList<>();
        List<RecipeProcessVisualizationPromptBuilder.GridPanel> panels = new ArrayList<>();
        for (VisualTarget target : targets) {
            VisualizationAsset step = ensurePrompted(userId, recipeId, target);
            steps.add(step);
            VisualFocus focus = step.getFocus() != null && step.getFocus() != VisualFocus.STORYBOARD
                    ? step.getFocus() : defaultFocus(target);
            panels.add(new RecipeProcessVisualizationPromptBuilder.GridPanel(step.getImagePrompt(), focus));
        }

        VisualizationAsset grid = gridAsset(recipeId, targets, panels);
        byte[][] generatedBytes = new byte[1][];
        if (!StringUtils.hasText(grid.getImageUrl())) {
            ImageGenerationClient.ImageGenerationSettings base = imageProfiles.settingsFor(targets.get(0).step().actionId());
            ImageGenerationClient.ImageGenerationSettings requested =
                    new ImageGenerationClient.ImageGenerationSettings(GRID_SIZE, GRID_SIZE, base.steps());
            VisualizationAsset pending = grid;
            grid = generateImage(pending, STEP_IMAGE_CORRELATION_TYPE,
                    () -> requestGridImage(userId, pending, panels, scene, previous, requested),
                    image -> generatedBytes[0] = image.data());
        }
        if (!StringUtils.hasText(grid.getImageUrl())) {
            String reason = grid.getImageFailureReason() != null ? grid.getImageFailureReason()
                    : "Panel image generation failed";
            return steps.stream().map(step -> failStep(step, reason)).toList();
        }

        try {
            byte[] data = generatedBytes[0] != null ? generatedBytes[0] : downloadImage(grid.getImageUrl());
            List<byte[]> parts = VisualizationGridSlicer.slice(data, targets.size());
            List<VisualizationAsset> cut = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) {
                cut.add(storePanel(steps.get(i), grid, parts.get(i)));
            }
            logger.debug("event=visualization_grid_cut gridKey={} panels={}", grid.getVisualizationKey(), parts.size());
            return cut;
        } catch (Exception e) {
            // The panel image stays stored: the next job only cuts it again.
            FailureLogger.logFailure(logger, "visualization_grid_cut_failed", e, "gridKey=" + grid.getVisualizationKey());
            String reason = "Could not cut the panel image: " + describeFailure(e);
            return steps.stream().map(step -> failStep(step, reason)).toList();
        }
    }

    /** The shared panel image's asset for these steps, created (with the panels' text as its prompt) when new. */
    private VisualizationAsset gridAsset(Long recipeId, List<VisualTarget> targets,
                                         List<RecipeProcessVisualizationPromptBuilder.GridPanel> panels) {
        String key = VisualizationKeyBuilder.buildGrid(String.valueOf(recipeId),
                targets.stream().map(VisualTarget::nodeId).toList());
        Optional<VisualizationAsset> existing = assetRepository.findByVisualizationKey(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        VisualizationAsset grid = new VisualizationAsset();
        grid.setId(sequenceGeneratorService.generateSequence(VisualizationAsset.SEQUENCE_NAME));
        grid.setVisualizationKey(key);
        grid.setType(VisualizationAssetType.ATOMIC);
        grid.setImagePrompt(panels.stream().map(RecipeProcessVisualizationPromptBuilder.GridPanel::stepText)
                .collect(Collectors.joining("\n")));
        return assetRepository.save(grid);
    }

    private byte[] downloadImage(String url) {
        if (imageStorageClient == null) {
            throw new IllegalStateException("No image storage to read the panel image back from");
        }
        return imageStorageClient.download(url).data();
    }

    /** Uploads one cut panel as the step's own image. */
    private VisualizationAsset storePanel(VisualizationAsset step, VisualizationAsset grid, byte[] panel) {
        if (imageStorageClient == null) {
            throw new IllegalStateException("No image storage to store the step's panel in");
        }
        String path = String.format("visualizations/%s/%s/%s.png", step.getVisualizationKey(), step.getId(),
                UUID.randomUUID());
        step.setImageUrl(imageStorageClient.upload(panel, "image/png", path));
        step.setImageFailureReason(null);
        step.setSourceGridKey(grid.getVisualizationKey());
        step.setResolvedModelKey(grid.getResolvedModelKey());
        step.setResolvedTier(grid.getResolvedTier());
        step.setUsedFallback(grid.isUsedFallback());
        step.setVideoUrl(null);
        return assetRepository.save(step);
    }

    private VisualizationAsset failStep(VisualizationAsset step, String reason) {
        step.setImageUrl(null);
        step.setImageFailureReason(reason);
        return assetRepository.save(step);
    }

    /**
     * The request of a shared panel image, drawn against the image before it in the preparation where the provider
     * takes reference images. When that fails, it is requested once more from its text alone.
     */
    private AiRequestOutcome<ImageGenerationClient.GeneratedImage> requestGridImage(
            Long userId, VisualizationAsset grid, List<RecipeProcessVisualizationPromptBuilder.GridPanel> panels,
            SceneReference scene, PreviousStep previous, ImageGenerationClient.ImageGenerationSettings requested) {
        if (previous != null && previous.image() != null) {
            try {
                return requestGridImage(userId, grid, panels, scene, requested, previous);
            } catch (Exception e) {
                FailureLogger.logFailure(logger, "visualization_image_reference_failed", e,
                        "visualizationKey=" + grid.getVisualizationKey() + " fallback=prompt_only");
            }
        }
        return requestGridImage(userId, grid, panels, scene, requested, null);
    }

    private AiRequestOutcome<ImageGenerationClient.GeneratedImage> requestGridImage(
            Long userId, VisualizationAsset grid, List<RecipeProcessVisualizationPromptBuilder.GridPanel> panels,
            SceneReference scene, ImageGenerationClient.ImageGenerationSettings requested, PreviousStep previous) {
        String visualizationKey = grid.getVisualizationKey();
        VisualizationImageProperties.Scene style = imageProfiles.scene();
        SceneContext equipment = SceneContext.orDefaults(
                scene != null ? scene.sceneContext() : storedSceneContext(visualizationKey), style.equipment());
        String dishContext = scene == null ? null : scene.dishContext();

        List<String> keyInputs = new ArrayList<>();
        keyInputs.add(promptBuilder.buildGridImageRequestText(panels, equipment, style, dishContext, false));
        keyInputs.add(previous == null ? "" : orBlank(previous.key()));
        keyInputs.add("image:" + requested.width() + "x" + requested.height() + ";steps=" + requested.steps());
        ImageGenerationClient.ImageGenerationSettings seeded = requested.withSeed(recipeSeed(visualizationKey));
        return queueService.executeInline(
                userId,
                AiCapability.TEXT_TO_IMAGE,
                null,
                null,
                STEP_IMAGE_CORRELATION_TYPE,
                visualizationKey,
                imageArtifactSpec(STEP_IMAGE_CORRELATION_TYPE, visualizationKey, keyInputs),
                selection -> {
                    ImageGenerationClient client = clientResolver.resolveImageClient(selection.model());
                    boolean sendPrevious = previous != null && client.supportsReferenceImages();
                    String text = promptBuilder.buildGridImageRequestText(panels, equipment, style, dishContext,
                            sendPrevious);
                    return client.generate(text, sendPrevious ? List.of(previous.image()) : List.of(),
                            seeded.withModel(selection.model().getProviderModelId()));
                }
        );
    }

    /**
     * How many image calls this process still needs: one per MAIN step without an image, and, with the focused view
     * on, one per group of Preparation steps sharing an image ({@link #chunkSizes}); steps already marked as looking
     * like the step before are free while look-alike skipping is on. With the focused view off, one per Preparation
     * flow without its image, as before.
     *
     * @param withImage the node ids that already show an image
     */
    public int missingImageCalls(Long recipeId, Process process, Set<String> withImage) {
        if (process.getType() == ProcessType.SUBPROCESS && !focusedViewEnabled()) {
            return (int) visualTargetNodeIds(process).stream().filter(id -> !withImage.contains(id)).count();
        }
        boolean skip = skipLookAlikeSteps();
        if (process.getType() != ProcessType.SUBPROCESS) {
            return (int) ProcessStepOrder.orderedSteps(process).stream()
                    .filter(node -> !withImage.contains(node.getId()))
                    .filter(node -> !(skip && marksLookAlike(recipeId, new VisualTarget(node.getId(), null, null))))
                    .count();
        }
        List<Process.ProcessNode> ordered = ProcessStepOrder.orderedSteps(process);
        int calls = 0;
        for (PreparationFlows.Flow flow : PreparationFlows.flowsOf(process)) {
            Set<String> nodeIds = new HashSet<>();
            flow.nodes().forEach(node -> nodeIds.add(node.getId()));
            int missing = (int) ordered.stream()
                    .filter(node -> nodeIds.contains(node.getId()) && !withImage.contains(node.getId()))
                    .filter(node -> !(skip && marksLookAlike(recipeId, new VisualTarget(node.getId(), null, null))))
                    .count();
            calls += chunkSizes(missing, preparationStepsPerImage()).size();
        }
        return calls;
    }

    /**
     * What a visualization job generates for one process: one image per MAIN step. A Preparation gets one image per
     * step with the focused view on (its steps grouped by flow, so a flow's steps can share one panel image, see
     * {@code preparationStepsPerImage}), or ONE image per flow with it off (attached to the step that makes the flow's
     * output), exactly as before.
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
        boolean perStep = focusedViewEnabled();
        for (PreparationFlows.Flow flow : PreparationFlows.flowsOf(process)) {
            if (flow.output() == null) continue;
            Set<String> nodeIds = new HashSet<>();
            flow.nodes().forEach(node -> nodeIds.add(node.getId()));
            // Continuity stays inside the flow: independent flows don't continue from one another.
            List<RecipeProcessVisualizationInput> inputs = new ArrayList<>();
            List<VisualTarget> stepTargets = new ArrayList<>();
            PrepFlow prepFlow = new PrepFlow(flow.output().getId(), flow.outputName());
            String previousExpectedOutput = null;
            for (Process.ProcessNode node : orderedSteps) {
                if (!nodeIds.contains(node.getId())) continue;
                Map<String, Object> data = node.getData() != null ? node.getData() : new LinkedHashMap<>();
                RecipeProcessVisualizationInput input = extractVisualizationInput(data, preparedNameByKey, previousExpectedOutput);
                inputs.add(input);
                stepTargets.add(new VisualTarget(node.getId(), input, null, prepFlow));
                previousExpectedOutput = input.expectedOutput();
            }
            if (inputs.isEmpty()) continue;
            if (perStep) {
                targets.addAll(stepTargets);
            } else {
                targets.add(new VisualTarget(flow.output().getId(), null, new FlowContext(flow.outputName(), inputs)));
            }
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

    /**
     * One image to generate, attached to {@code nodeId}: a MAIN step ({@code step}), a whole Preparation flow
     * ({@code flow}, with the focused view off), or one Preparation step ({@code step} with {@code prepFlow}, the flow
     * it belongs to: its steps may share one panel image).
     */
    public record VisualTarget(String nodeId, RecipeProcessVisualizationInput step, FlowContext flow, PrepFlow prepFlow) {

        public VisualTarget(String nodeId, RecipeProcessVisualizationInput step, FlowContext flow) {
            this(nodeId, step, flow, null);
        }

        /** Whether this is one step of a Preparation flow (drawn alone, or as a panel of a shared image). */
        public boolean isPreparationStep() {
            return prepFlow != null;
        }
    }

    /** The Preparation flow a step belongs to: its id (the flow's output step) and its named output. */
    public record PrepFlow(String id, String outputName) {
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

    private VisualizationAsset generatePrompt(Long userId, String visualizationKey, Supplier<String> userPrompt,
                                              VisualFocus focus) {
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
                                .temperature(generationParams.getVisualization().getTemperature())
                                .maxTokens(generationParams.getVisualization().getMaxTokens())
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
            // Only a prompt that was actually written is a focused one; an empty answer stays unmarked.
            asset.setFocus(StringUtils.hasText(outcome.value()) ? focus : null);
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
    private VisualizationAsset generateImage(Long userId, VisualizationAsset asset, SceneReference scene, PreviousStep previous,
                                             String correlationType, ImageGenerationClient.ImageGenerationSettings settings) {
        ImageGenerationClient.ImageGenerationSettings requested =
                settings == null ? ImageGenerationClient.ImageGenerationSettings.NONE : settings;
        // A prompt without a focus is self-contained (written before the focused view, or with it off) and is
        // requested exactly as it always was.
        return generateImage(asset, correlationType, () -> asset.getFocus() == null
                ? requestImage(userId, asset, scene, correlationType, requested)
                : requestFocusedImage(userId, asset, scene, previous, correlationType, requested), null);
    }

    /**
     * Runs {@code request} and stores its image on {@code asset} (see the 6-argument form).
     *
     * @param generated receives the generated image when it is stored (the bytes a panel image is cut from), or null
     */
    private VisualizationAsset generateImage(VisualizationAsset asset, String correlationType,
                                             Supplier<AiRequestOutcome<ImageGenerationClient.GeneratedImage>> request,
                                             java.util.function.Consumer<ImageGenerationClient.GeneratedImage> generated) {
        String visualizationKey = asset.getVisualizationKey();
        try {
            AiRequestOutcome<ImageGenerationClient.GeneratedImage> outcome = request.get();

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
            if (generated != null) generated.accept(outcome.value());
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

    /** The request of a self-contained prompt: the dish context line, the scene anchor as a reference, the prompt. */
    private AiRequestOutcome<ImageGenerationClient.GeneratedImage> requestImage(
            Long userId, VisualizationAsset asset, SceneReference scene, String correlationType,
            ImageGenerationClient.ImageGenerationSettings requested) {
        String visualizationKey = asset.getVisualizationKey();
        String dishContext = scene == null ? null : scene.dishContext();
        ImageGenerationClient.ReferenceImage reference = scene == null ? null : scene.image();
        // Without a scene the key is exactly as before, so payloads retained before scene anchors stay reusable.
        List<String> keyInputs = new ArrayList<>();
        keyInputs.add(asset.getImagePrompt());
        if (scene != null) {
            keyInputs.add(orBlank(dishContext));
            keyInputs.add(orBlank(scene.anchorKey()));
        }
        // Likewise only profiled images carry their settings, so an image of another size is never reused.
        if (!requested.isNone()) {
            keyInputs.add("image:" + requested.width() + "x" + requested.height() + ";steps=" + requested.steps());
        }
        return queueService.executeInline(
                userId,
                AiCapability.TEXT_TO_IMAGE,
                null,
                null,
                correlationType,
                visualizationKey,
                imageArtifactSpec(correlationType, visualizationKey, keyInputs),
                selection -> {
                    ImageGenerationClient client = clientResolver.resolveImageClient(selection.model());
                    // Only a provider that really sends the reference is told about it.
                    boolean withReference = reference != null && client.supportsReferenceImages();
                    String text = promptBuilder.buildImageRequestText(asset.getImagePrompt(), dishContext, withReference);
                    return client.generate(text, withReference ? List.of(reference) : List.of(),
                            requested.withModel(selection.model().getProviderModelId()));
                }
        );
    }

    /**
     * The request of a focused image: the scene is written around the step's own text, and the image is drawn
     * against the scene anchor (for a focus the anchor shows) and the previous image of its chain. When a request
     * with references fails, the image is requested once more from its prompt alone, so a reference the provider
     * cannot use never costs the step its image.
     */
    private AiRequestOutcome<ImageGenerationClient.GeneratedImage> requestFocusedImage(
            Long userId, VisualizationAsset asset, SceneReference scene, PreviousStep previous, String correlationType,
            ImageGenerationClient.ImageGenerationSettings requested) {
        boolean withAnchor = scene != null && scene.image() != null && asset.getFocus().usesSceneAnchor();
        boolean withPrevious = previous != null && previous.image() != null;
        if (withAnchor || withPrevious) {
            try {
                return requestFocusedImage(userId, asset, scene, correlationType, requested,
                        withAnchor ? scene : null, withPrevious ? previous : null);
            } catch (Exception e) {
                FailureLogger.logFailure(logger, "visualization_image_reference_failed", e,
                        "visualizationKey=" + asset.getVisualizationKey() + " fallback=prompt_only");
            }
        }
        return requestFocusedImage(userId, asset, scene, correlationType, requested, null, null);
    }

    private AiRequestOutcome<ImageGenerationClient.GeneratedImage> requestFocusedImage(
            Long userId, VisualizationAsset asset, SceneReference scene, String correlationType,
            ImageGenerationClient.ImageGenerationSettings requested, SceneReference anchor, PreviousStep previous) {
        String visualizationKey = asset.getVisualizationKey();
        VisualFocus focus = asset.getFocus();
        VisualizationImageProperties.Scene style = imageProfiles.scene();
        // The equipment is part of the prompt, not of the anchor: it is used with scene anchors switched off too.
        SceneContext equipment = SceneContext.orDefaults(
                scene != null ? scene.sceneContext() : storedSceneContext(visualizationKey), style.equipment());
        String dishContext = scene == null ? null : scene.dishContext();

        // The whole assembled text is part of the key: a changed scene or framing never reuses an older image.
        List<String> keyInputs = new ArrayList<>();
        keyInputs.add(promptBuilder.buildFocusedImageRequestText(asset.getImagePrompt(), focus, equipment, style,
                dishContext, RecipeProcessVisualizationPromptBuilder.References.NONE));
        keyInputs.add(anchor == null ? "" : orBlank(anchor.anchorKey()));
        keyInputs.add(previous == null ? "" : orBlank(previous.key()));
        if (!requested.isNone()) {
            keyInputs.add("image:" + requested.width() + "x" + requested.height() + ";steps=" + requested.steps());
        }
        // One seed per recipe: a small aid for providers that take one, never the consistency mechanism itself.
        ImageGenerationClient.ImageGenerationSettings seeded = requested.withSeed(recipeSeed(visualizationKey));
        return queueService.executeInline(
                userId,
                AiCapability.TEXT_TO_IMAGE,
                null,
                null,
                correlationType,
                visualizationKey,
                imageArtifactSpec(correlationType, visualizationKey, keyInputs),
                selection -> {
                    ImageGenerationClient client = clientResolver.resolveImageClient(selection.model());
                    List<ImageGenerationClient.ReferenceImage> references = new ArrayList<>();
                    boolean sendPrevious = false;
                    boolean sendAnchor = false;
                    if (client.supportsReferenceImages()) {
                        // The previous step already shows the equipment, so it wins when only one image fits.
                        sendPrevious = previous != null;
                        sendAnchor = anchor != null && (!sendPrevious || client.maxReferenceImages() >= 2);
                        if (sendAnchor) references.add(anchor.image());
                        if (sendPrevious) references.add(previous.image());
                    }
                    String text = promptBuilder.buildFocusedImageRequestText(asset.getImagePrompt(), focus, equipment,
                            style, dishContext,
                            new RecipeProcessVisualizationPromptBuilder.References(sendAnchor, sendPrevious));
                    return client.generate(text, references, seeded.withModel(selection.model().getProviderModelId()));
                }
        );
    }

    private AiArtifactSpec<ImageGenerationClient.GeneratedImage> imageArtifactSpec(
            String correlationType, String visualizationKey, List<String> keyInputs) {
        return AiArtifactSpec.of(
                AiArtifactKeyBuilder.build(
                        AiCapability.TEXT_TO_IMAGE,
                        correlationType,
                        visualizationKey,
                        keyInputs.toArray(String[]::new)),
                generatedImageCodec,
                VisualizationImageArtifactConsumer.CONSUMER_ID
        );
    }

    /** The equipment stored on the recipe's scene asset, or null when it has none (the defaults then apply). */
    private SceneContext storedSceneContext(String visualizationKey) {
        int separator = visualizationKey.indexOf("::");
        if (separator < 0) {
            return null;
        }
        return assetRepository.findByVisualizationKey(VisualizationKeyBuilder.buildScene(visualizationKey.substring(0, separator)))
                .map(VisualizationAsset::getSceneContext)
                .orElse(null);
    }

    /** A stable seed for every image of one recipe, from the recipe part of the key ({@code recipeId::...}). */
    static long recipeSeed(String visualizationKey) {
        int separator = visualizationKey.indexOf("::");
        String recipe = separator < 0 ? visualizationKey : visualizationKey.substring(0, separator);
        return Integer.toUnsignedLong(recipe.hashCode());
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
                previousExpectedOutput,
                actionId
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
