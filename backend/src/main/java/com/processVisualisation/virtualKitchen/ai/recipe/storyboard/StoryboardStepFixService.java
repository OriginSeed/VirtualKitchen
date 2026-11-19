package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

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
import com.processVisualisation.virtualKitchen.ai.platform.response.AiJsonResponseSupport;
import com.processVisualisation.virtualKitchen.ai.platform.routing.ModelSelectionOutcome;
import com.processVisualisation.virtualKitchen.ai.provider.AiClient;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient;
import com.processVisualisation.virtualKitchen.ai.provider.ImageStorageClient;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiRequest;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiResponse;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutputSchema;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutputSchema.Field;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.dto.StoryboardStepFixResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.RecipeStoryboard;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.StoryboardStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.StoryboardStepFix;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationKeyBuilder;
import com.processVisualisation.virtualKitchen.common.concurrent.NamedTask;
import com.processVisualisation.virtualKitchen.common.concurrent.TaskPool;
import com.processVisualisation.virtualKitchen.common.logging.FailureLogger;
import com.processVisualisation.virtualKitchen.common.persistence.SequenceGeneratorService;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessNodeKind;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import com.processVisualisation.virtualKitchen.recipe.repository.ProcessRepository;
import com.processVisualisation.virtualKitchen.recipe.repository.RecipeTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Fixes one step's image from the cook's feedback, as a small background job tracked in a {@link StoryboardStepFix}:
 * <ol>
 *   <li>a text call rewrites the step's image prompt so it applies the feedback and keeps the recipe's look;</li>
 *   <li>one image request draws it again, with the step's current image (or its storyboard panel) as the reference
 *       when the image model takes references;</li>
 *   <li>the new image and prompt are put on the step, which keeps the old ones on the fix so it can be reverted.</li>
 * </ol>
 * A fixed image counts as the step's own: a later "Update" Visualize run keeps it, only "Redo everything" replaces it.
 */
@Service
public class StoryboardStepFixService {

    private static final Logger log = LoggerFactory.getLogger(StoryboardStepFixService.class);

    static final String PROMPT_CORRELATION_TYPE = "recipe-storyboard-step-fix-prompt";
    static final String IMAGE_CORRELATION_TYPE = "recipe-storyboard-step-fix";
    /** {@link VisualizationAsset#getSourceGridKey()} of a fixed step image; the attacher treats it as the step's own. */
    static final String SOURCE_PREFIX = "storyboard-fix:";
    static final int MAX_FEEDBACK_CHARS = 1000;

    private final StoryboardStepFixRepository fixRepository;
    private final RecipeStoryboardRepository storyboardRepository;
    private final RecipeTemplateRepository recipeTemplateRepository;
    private final ProcessRepository processRepository;
    private final VisualizationAssetRepository assetRepository;
    private final SequenceGeneratorService sequenceGeneratorService;
    private final AiRequestQueueService queueService;
    private final AiClientResolver clientResolver;
    private final AiJsonResponseSupport responseSupport;
    private final AiArtifactService artifactService;
    private final GeneratedImageCodec generatedImageCodec;
    private final StoryboardStepFixArtifactConsumer imageConsumer;
    private final ImageStorageClient imageStorageClient;
    private final StoryboardProperties properties;
    private final MongoTemplate mongoTemplate;
    private final TaskPool taskPool;
    private final Map<String, Object> responseSchema = RecipeProcessOutputSchema.object(List.of(
            new Field("imagePrompt", RecipeProcessOutputSchema.string(), true)));

    public StoryboardStepFixService(StoryboardStepFixRepository fixRepository, RecipeStoryboardRepository storyboardRepository,
                                    RecipeTemplateRepository recipeTemplateRepository, ProcessRepository processRepository,
                                    VisualizationAssetRepository assetRepository, SequenceGeneratorService sequenceGeneratorService,
                                    AiRequestQueueService queueService, AiClientResolver clientResolver,
                                    AiJsonResponseSupport responseSupport, AiArtifactService artifactService,
                                    GeneratedImageCodec generatedImageCodec, StoryboardStepFixArtifactConsumer imageConsumer,
                                    ImageStorageClient imageStorageClient, StoryboardProperties properties,
                                    MongoTemplate mongoTemplate,
                                    @Qualifier("visualizationOrchestratorTaskPool") TaskPool taskPool) {
        this.fixRepository = fixRepository;
        this.storyboardRepository = storyboardRepository;
        this.recipeTemplateRepository = recipeTemplateRepository;
        this.processRepository = processRepository;
        this.assetRepository = assetRepository;
        this.sequenceGeneratorService = sequenceGeneratorService;
        this.queueService = queueService;
        this.clientResolver = clientResolver;
        this.responseSupport = responseSupport;
        this.artifactService = artifactService;
        this.generatedImageCodec = generatedImageCodec;
        this.imageConsumer = imageConsumer;
        this.imageStorageClient = imageStorageClient;
        this.properties = properties;
        this.mongoTemplate = mongoTemplate;
        this.taskPool = taskPool;
    }

    /**
     * Starts fixing a step's image and returns at once. A fix already running for the step is returned instead.
     *
     * @throws NoSuchElementException if the recipe, process or step does not exist
     * @throws RecipeAccessDeniedException if the caller does not own the recipe
     * @throws RecipeStoryboardException if the feedback is empty or too long
     */
    public StoryboardStepFixResponseDTO startFix(Long userId, Long recipeId, Long processId, String stepId, String feedback) {
        requireOwned(recipeId, userId);
        String text = feedback == null ? "" : feedback.trim();
        if (text.isEmpty()) {
            throw RecipeStoryboardException.unprocessable("Say what should change in the image.");
        }
        if (text.length() > MAX_FEEDBACK_CHARS) {
            throw RecipeStoryboardException.unprocessable("Keep the feedback under " + MAX_FEEDBACK_CHARS + " characters.");
        }
        requireStep(recipeId, processId, stepId);

        String activeKey = activeKey(recipeId, stepId);
        Optional<StoryboardStepFix> running = fixRepository.findByActiveKey(activeKey);
        if (running.isPresent()) {
            return toDto(running.get(), true);
        }

        VisualizationAsset asset = assetRepository.findByVisualizationKey(VisualizationKeyBuilder.build(String.valueOf(recipeId), stepId))
                .orElse(null);
        StoryboardStepFix fix = new StoryboardStepFix();
        fix.setId(UUID.randomUUID().toString());
        fix.setRecipeId(recipeId);
        fix.setProcessId(processId);
        fix.setStepId(stepId);
        fix.setUserId(userId);
        fix.setStatus(StoryboardStatus.QUEUED);
        fix.setFeedback(text);
        fix.setActiveKey(activeKey);
        fix.setPreviousPrompt(asset == null ? null : asset.getImagePrompt());
        fix.setPreviousImageUrl(asset == null ? null : asset.getImageUrl());
        fix.setPreviousSourceGridKey(asset == null ? null : asset.getSourceGridKey());
        fix.setCreatedAt(Instant.now());
        fix.setUpdatedAt(Instant.now());
        try {
            fixRepository.insert(fix);
        } catch (DuplicateKeyException e) {
            return fixRepository.findByActiveKey(activeKey).map(winner -> toDto(winner, true)).orElseThrow(() -> e);
        }
        log.info("event=storyboard_step_fix_queued fixId={} recipeId={} stepId={}", fix.getId(), recipeId, stepId);
        taskPool.submit(new NamedTask<>(fix.getId(), () -> {
            run(fix);
            return null;
        }));
        return toDto(fix, false);
    }

    /** The status (and, once done, the new image) of a fix the caller started for this recipe. */
    public StoryboardStepFixResponseDTO getFix(Long userId, Long recipeId, String fixId) {
        return toDto(findOwned(userId, recipeId, fixId), false);
    }

    /**
     * Puts the step back to the prompt and image it had before this fix. Only the fix that made the step's current
     * image can be reverted (a later fix or Visualize run has replaced it otherwise).
     */
    public StoryboardStepFixResponseDTO revert(Long userId, Long recipeId, String fixId) {
        requireOwned(recipeId, userId);
        StoryboardStepFix fix = findOwned(userId, recipeId, fixId);
        if (fix.getStatus() != StoryboardStatus.COMPLETED || fix.getRevertedAt() != null) {
            throw RecipeStoryboardException.conflict("Only a finished fix can be undone, and only once.");
        }
        String key = VisualizationKeyBuilder.build(String.valueOf(recipeId), fix.getStepId());
        VisualizationAsset asset = assetRepository.findByVisualizationKey(key).orElse(null);
        if (asset == null || !Objects.equals(asset.getSourceGridKey(), SOURCE_PREFIX + fix.getId())) {
            throw RecipeStoryboardException.conflict("This step's image has changed since the fix, so it can no longer be undone.");
        }
        asset.setImagePrompt(fix.getPreviousPrompt());
        asset.setImageUrl(fix.getPreviousImageUrl());
        asset.setSourceGridKey(fix.getPreviousSourceGridKey());
        asset = assetRepository.save(asset);
        processRepository.setStepVisualization(fix.getProcessId(), fix.getStepId(),
                StringUtils.hasText(asset.getImageUrl()) ? asset.getId() : null, asset.getImagePrompt(), asset.getImageUrl());
        Instant now = Instant.now();
        mongoTemplate.updateFirst(query(where("_id").is(fixId)), new Update().set("revertedAt", now).set("updatedAt", now),
                StoryboardStepFix.class);
        fix.setRevertedAt(now);
        log.info("event=storyboard_step_fix_reverted fixId={} recipeId={} stepId={}", fixId, recipeId, fix.getStepId());
        return toDto(fix, false);
    }

    // ---------------------------------------------------------------------------------------------- the job

    private void run(StoryboardStepFix fix) {
        String id = fix.getId();
        try {
            update(id, new Update().set("status", StoryboardStatus.IN_PROGRESS));
            RecipeStoryboard.Panel panel = panelOf(fix.getRecipeId(), fix.getStepId());
            RecipeStoryboard storyboard = latestStoryboard(fix.getRecipeId());

            String basePrompt = StringUtils.hasText(fix.getPreviousPrompt()) ? fix.getPreviousPrompt()
                    : panel != null && StringUtils.hasText(panel.getImagePrompt()) ? panel.getImagePrompt()
                    : panel != null ? panel.getDescription() : null;
            Revised revised = revisePrompt(fix, basePrompt, panel, storyboard == null ? null : storyboard.getDishContext());
            fix.setNewPrompt(revised.prompt());
            fix.setPromptFromTemplate(revised.fromTemplate());
            update(id, new Update().set("newPrompt", revised.prompt()).set("promptFromTemplate", revised.fromTemplate()));

            String referenceUrl = StringUtils.hasText(fix.getPreviousImageUrl()) ? fix.getPreviousImageUrl()
                    : panel != null ? panel.getImageUrl() : null;
            ImageGenerationClient.ReferenceImage reference = readReference(referenceUrl);
            Drawn drawn = draw(fix, revised.prompt(), reference);

            VisualizationAsset asset = attach(fix, revised.prompt(), drawn.imageUrl(), drawn.modelKey());
            Instant now = Instant.now();
            update(id, new Update()
                    .set("status", StoryboardStatus.COMPLETED)
                    .set("newImageUrl", drawn.imageUrl())
                    .set("usedReference", drawn.usedReference())
                    .set("imageModelKey", drawn.modelKey())
                    .set("visualizationAssetId", asset.getId())
                    .set("completedAt", now)
                    .unset("activeKey"));
            log.info("event=storyboard_step_fix_completed fixId={} stepId={} usedReference={} promptFromTemplate={}",
                    id, fix.getStepId(), drawn.usedReference(), revised.fromTemplate());
        } catch (Exception e) {
            FailureLogger.logFailure(log, "storyboard_step_fix_failed", e, "fixId=" + id + " stepId=" + fix.getStepId());
            update(id, new Update()
                    .set("status", StoryboardStatus.FAILED)
                    .set("errorMessage", StringUtils.hasText(e.getMessage()) ? e.getMessage() : "The image could not be fixed. Please try again.")
                    .set("completedAt", Instant.now())
                    .unset("activeKey"));
        }
    }

    record Revised(String prompt, boolean fromTemplate) {
    }

    record Output(String imagePrompt) {
    }

    /** The prompt rewritten to apply the feedback; on any failure, the old prompt with the correction appended. */
    Revised revisePrompt(StoryboardStepFix fix, String basePrompt, RecipeStoryboard.Panel panel, String dishContext) {
        String fallback = (StringUtils.hasText(basePrompt) ? basePrompt.trim() + " " : "")
                + "Correction requested by the cook: " + fix.getFeedback();
        String userPrompt = buildRevisionPrompt(fix.getFeedback(), basePrompt, panel, dishContext);
        try {
            String written = queueService.executeBounded(fix.getUserId(), AiCapability.TEXT_TO_TEXT, null, null,
                    PROMPT_CORRELATION_TYPE, fix.getId(), selection -> {
                        AiClient client = clientResolver.resolveTextClient(selection.model());
                        AiRequest request = AiRequest.builder()
                                .model(selection.model().getProviderModelId())
                                .systemPrompt(SYSTEM_PROMPT)
                                .userPrompt(userPrompt)
                                .temperature(0.4)
                                .maxTokens(1200)
                                .responseFormat("json_object")
                                .responseSchema(responseSchema)
                                .responseSchemaName("storyboard_step_fix_prompt")
                                .operation("STORYBOARD_STEP_FIX_PROMPT")
                                .build();
                        long start = System.nanoTime();
                        AiResponse response = client.chat(request);
                        String content = response == null ? null : response.getContent();
                        String prompt = null;
                        try {
                            Output output = StringUtils.hasText(content) ? responseSupport.readValue(content, Output.class) : null;
                            prompt = output == null ? null : output.imagePrompt();
                        } catch (Exception parse) {
                            // Unusable answer: the fallback prompt is used below.
                        }
                        responseSupport.persistAIResponse(PROMPT_CORRELATION_TYPE, userPrompt, response, StringUtils.hasText(prompt),
                                (System.nanoTime() - start) / 1_000_000L, StringUtils.hasText(prompt) ? List.of() : List.of("no imagePrompt"));
                        return prompt;
                    }).value();
            if (StringUtils.hasText(written)) {
                return new Revised(written.trim(), false);
            }
        } catch (Exception e) {
            FailureLogger.logFailure(log, "storyboard_step_fix_prompt_failed", e, "fixId=" + fix.getId() + " fallback=template");
        }
        return new Revised(fallback, true);
    }

    static final String SYSTEM_PROMPT = """
            You rewrite the image prompt of ONE cooking step so a new image fixes what the cook said is wrong.
            - Apply the cook's feedback exactly; it overrides anything in the old prompt it contradicts.
            - Keep everything the feedback does not mention: the dish, ingredients, equipment, camera angle,
              lighting and photographic style, so the new image still matches the rest of the recipe.
            - Describe one single photo of this one step (not panels, not a grid), with no text or labels in it.
            - Return JSON {"imagePrompt": "..."} only.
            """;

    static String buildRevisionPrompt(String feedback, String basePrompt, RecipeStoryboard.Panel panel, String dishContext) {
        StringBuilder out = new StringBuilder();
        if (StringUtils.hasText(dishContext)) out.append("DISH: ").append(dishContext.trim()).append('\n');
        if (panel != null) {
            if (StringUtils.hasText(panel.getDescription())) out.append("STEP: ").append(panel.getDescription().trim()).append('\n');
            if (panel.getIngredients() != null && !panel.getIngredients().isEmpty()) {
                out.append("INGREDIENTS: ").append(String.join(", ", panel.getIngredients())).append('\n');
            }
            if (panel.getEquipment() != null && !panel.getEquipment().isEmpty()) {
                out.append("EQUIPMENT: ").append(String.join(", ", panel.getEquipment())).append('\n');
            }
            if (StringUtils.hasText(panel.getExpectedState())) out.append("EXPECTED STATE: ").append(panel.getExpectedState().trim()).append('\n');
        }
        out.append("OLD IMAGE PROMPT: ").append(StringUtils.hasText(basePrompt) ? basePrompt.trim() : "(none)").append('\n');
        out.append("COOK'S FEEDBACK: ").append(feedback.trim());
        return out.toString();
    }

    record Drawn(String imageUrl, String modelKey, boolean usedReference) {
    }

    private Drawn draw(StoryboardStepFix fix, String prompt, ImageGenerationClient.ReferenceImage reference) {
        ImageGenerationClient.ImageGenerationSettings requested = new ImageGenerationClient.ImageGenerationSettings(
                properties.getImageWidth(), properties.getImageHeight(), null);
        boolean[] usedReference = {false};
        AiArtifactSpec<ImageGenerationClient.GeneratedImage> spec = AiArtifactSpec.of(
                AiArtifactKeyBuilder.build(AiCapability.TEXT_TO_IMAGE, IMAGE_CORRELATION_TYPE, fix.getId(), prompt,
                        fix.getFeedback(), reference == null ? "no-reference" : fingerprint(reference.data()),
                        "image:" + requested.width() + "x" + requested.height()),
                generatedImageCodec, StoryboardStepFixArtifactConsumer.CONSUMER_ID);
        AiRequestOutcome<ImageGenerationClient.GeneratedImage> outcome = queueService.executeInline(
                fix.getUserId(), AiCapability.TEXT_TO_IMAGE, null, null, IMAGE_CORRELATION_TYPE, fix.getId(), spec,
                selection -> {
                    ImageGenerationClient client = clientResolver.resolveImageClient(selection.model());
                    ImageGenerationClient.ImageGenerationSettings settings = requested.withModel(selection.model().getProviderModelId());
                    if (reference != null && client.supportsReferenceImages()) {
                        usedReference[0] = true;
                        return client.generate(REFERENCE_PREFIX + prompt, List.of(reference), settings);
                    }
                    return client.generate(prompt, List.of(), settings);
                });

        ModelSelectionOutcome selection = outcome.selection();
        AiArtifact artifact = outcome.artifact();
        if (artifact == null) {
            artifact = new AiArtifact();
            artifact.setCorrelationId(fix.getId());
            artifact.setCorrelationType(IMAGE_CORRELATION_TYPE);
            artifact.setCapability(AiCapability.TEXT_TO_IMAGE);
            artifact.setProducedByModelKey(selection.model().getKey());
            artifact.setProducedByTier(selection.model().getTier());
            artifact.setUsedFallback(selection.usedFallback());
        }
        String imageUrl = imageConsumer.consume(artifact, outcome.value());
        artifactService.markConsumed(artifact.getId());
        return new Drawn(imageUrl, selection.model().getKey(), usedReference[0]);
    }

    static final String REFERENCE_PREFIX = "Redraw the reference image as described below. Keep its dish, equipment, "
            + "camera angle, lighting and style unless the description changes them.\n\n";

    /** Puts the new prompt and image on the step's asset and node, as the step's own image. */
    private VisualizationAsset attach(StoryboardStepFix fix, String prompt, String imageUrl, String modelKey) {
        String key = VisualizationKeyBuilder.build(String.valueOf(fix.getRecipeId()), fix.getStepId());
        VisualizationAsset asset = assetRepository.findByVisualizationKey(key).orElseGet(() -> {
            VisualizationAsset created = new VisualizationAsset();
            created.setId(sequenceGeneratorService.generateSequence(VisualizationAsset.SEQUENCE_NAME));
            created.setVisualizationKey(key);
            created.setType(VisualizationAssetType.ATOMIC);
            return created;
        });
        asset.setImagePrompt(prompt);
        asset.setImageUrl(imageUrl);
        asset.setImageFailureReason(null);
        asset.setFocus(null);
        asset.setReusesPrevious(null);
        asset.setSourceGridKey(SOURCE_PREFIX + fix.getId());
        asset.setResolvedModelKey(modelKey);
        asset = assetRepository.save(asset);
        processRepository.setStepVisualization(fix.getProcessId(), fix.getStepId(), asset.getId(), prompt, imageUrl);
        return asset;
    }

    /** The step's current image (or its panel's), as a reference; null when there is none or it cannot be read. */
    private ImageGenerationClient.ReferenceImage readReference(String url) {
        if (!StringUtils.hasText(url) || imageStorageClient == null) return null;
        try {
            ImageStorageClient.StoredImage stored = imageStorageClient.download(url);
            return stored == null || stored.data() == null || stored.data().length == 0 ? null
                    : new ImageGenerationClient.ReferenceImage(stored.mimeType(), stored.data());
        } catch (RuntimeException e) {
            log.debug("event=storyboard_step_fix_reference_unreadable errorType={}", e.getClass().getSimpleName());
            return null;
        }
    }

    private RecipeStoryboard latestStoryboard(Long recipeId) {
        return storyboardRepository.findFirstByRecipeIdAndStatusOrderByCreatedAtDesc(recipeId, StoryboardStatus.COMPLETED).orElse(null);
    }

    /** The panel of the latest storyboard that covers the step, if any. */
    private RecipeStoryboard.Panel panelOf(Long recipeId, String stepId) {
        RecipeStoryboard storyboard = latestStoryboard(recipeId);
        if (storyboard == null || storyboard.getPanels() == null) return null;
        return storyboard.getPanels().stream()
                .filter(panel -> panel.getSourceStepIds() != null && panel.getSourceStepIds().contains(stepId))
                .findFirst().orElse(null);
    }

    private void requireStep(Long recipeId, Long processId, String stepId) {
        Process process = processId == null ? null : processRepository.findById(processId).orElse(null);
        if (process == null || !Objects.equals(process.getRecipeId(), recipeId) || process.getNodes() == null
                || process.getNodes().stream().noneMatch(node -> Objects.equals(node.getId(), stepId) && node.getKind() == ProcessNodeKind.STEP)) {
            throw new NoSuchElementException("Step not found: " + stepId);
        }
    }

    private RecipeTemplate requireOwned(Long recipeId, Long userId) {
        RecipeTemplate recipe = recipeTemplateRepository.findById(recipeId)
                .orElseThrow(() -> new NoSuchElementException("Recipe not found: " + recipeId));
        if (userId == null || !userId.equals(recipe.getCreatedBy())) {
            throw new RecipeAccessDeniedException("You do not have permission to modify this recipe");
        }
        return recipe;
    }

    private StoryboardStepFix findOwned(Long userId, Long recipeId, String fixId) {
        return fixRepository.findById(fixId)
                .filter(found -> Objects.equals(found.getUserId(), userId) && Objects.equals(found.getRecipeId(), recipeId))
                .orElseThrow(() -> new NoSuchElementException("Step image fix not found: " + fixId));
    }

    private void update(String fixId, Update update) {
        mongoTemplate.updateFirst(query(where("_id").is(fixId)), update.set("updatedAt", Instant.now()), StoryboardStepFix.class);
    }

    static String activeKey(Long recipeId, String stepId) {
        return "fix:" + recipeId + ":" + stepId;
    }

    private static String fingerprint(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).substring(0, 16);
        } catch (Exception e) {
            return String.valueOf(java.util.Arrays.hashCode(data));
        }
    }

    static StoryboardStepFixResponseDTO toDto(StoryboardStepFix fix, boolean reused) {
        return StoryboardStepFixResponseDTO.builder()
                .fixId(fix.getId())
                .recipeId(fix.getRecipeId())
                .processId(fix.getProcessId())
                .stepId(fix.getStepId())
                .status(fix.getStatus() == null ? null : fix.getStatus().name())
                .feedback(fix.getFeedback())
                .reused(reused)
                .imagePrompt(fix.getNewPrompt())
                .imageUrl(fix.getNewImageUrl())
                .visualizationAssetId(fix.getVisualizationAssetId())
                .previousImageUrl(fix.getPreviousImageUrl())
                .previousPrompt(fix.getPreviousPrompt())
                .usedReference(fix.isUsedReference())
                .promptFromTemplate(fix.isPromptFromTemplate())
                .errorMessage(fix.getErrorMessage())
                .reverted(fix.getRevertedAt() != null)
                .createdAt(fix.getCreatedAt())
                .completedAt(fix.getCompletedAt())
                .build();
    }
}
