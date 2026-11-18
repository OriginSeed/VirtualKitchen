package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifact;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifactKeyBuilder;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifactService;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifactSpec;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.codec.GeneratedImageCodec;
import com.processVisualisation.virtualKitchen.ai.platform.dispatch.AiClientResolver;
import com.processVisualisation.virtualKitchen.ai.platform.logging.OperationLog;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestOutcome;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestQueueService;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.routing.ModelSelectionOutcome;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient;
import com.processVisualisation.virtualKitchen.ai.provider.ImageStorageClient;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.dto.RecipeStoryboardResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.RecipeStoryboard;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.StoryboardStage;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.StoryboardStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService.ProcessStepPreparation;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationImageProfiles;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationJobRepository;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.model.VisualizationJobStatus;
import com.processVisualisation.virtualKitchen.common.concurrent.NamedTask;
import com.processVisualisation.virtualKitchen.common.concurrent.TaskPool;
import com.processVisualisation.virtualKitchen.common.logging.FailureLogger;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessType;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import com.processVisualisation.virtualKitchen.recipe.model.Visibility;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Generates a recipe's storyboard as a background job tracked in its {@link RecipeStoryboard} document:
 * <ol>
 *   <li>PLAN: one text call groups the recipe's nodes into panels ({@link StoryboardPlanner});</li>
 *   <li>PROMPT: a second text call writes the one image prompt from them ({@link StoryboardMasterPromptWriter});</li>
 *   <li>IMAGE: exactly one image request draws the combined image;</li>
 *   <li>CROP: the image is cut into its panels, which are put on the steps they stand for
 *       ({@link StoryboardPanelAttacher}).</li>
 * </ol>
 * One storyboard runs per recipe at a time. A new start after a failure on an unchanged recipe continues from the
 * stage that failed, so a failed image request does not pay for the text calls again.
 */
@Service
public class RecipeStoryboardJobService {

    private static final Logger log = LoggerFactory.getLogger(RecipeStoryboardJobService.class);

    static final String IMAGE_CORRELATION_TYPE = "recipe-storyboard-image";

    private final RecipeStoryboardRepository storyboardRepository;
    private final RecipeTemplateRepository recipeTemplateRepository;
    private final ProcessRepository processRepository;
    private final RecipeProcessVisualizationService visualizationService;
    private final VisualizationJobRepository visualizationJobRepository;
    private final StoryboardPlanner planner;
    private final StoryboardMasterPromptWriter masterPromptWriter;
    private final StoryboardPanelAttacher panelAttacher;
    private final StoryboardImageArtifactConsumer imageArtifactConsumer;
    private final AiRequestQueueService queueService;
    private final AiClientResolver clientResolver;
    private final AiArtifactService artifactService;
    private final GeneratedImageCodec generatedImageCodec;
    private final VisualizationImageProfiles imageProfiles;
    private final StoryboardProperties properties;
    private final MongoTemplate mongoTemplate;
    private final TaskPool orchestratorTaskPool;
    private ImageStorageClient imageStorageClient;

    public RecipeStoryboardJobService(
            RecipeStoryboardRepository storyboardRepository,
            RecipeTemplateRepository recipeTemplateRepository,
            ProcessRepository processRepository,
            RecipeProcessVisualizationService visualizationService,
            VisualizationJobRepository visualizationJobRepository,
            StoryboardPlanner planner,
            StoryboardMasterPromptWriter masterPromptWriter,
            StoryboardPanelAttacher panelAttacher,
            StoryboardImageArtifactConsumer imageArtifactConsumer,
            AiRequestQueueService queueService,
            AiClientResolver clientResolver,
            AiArtifactService artifactService,
            GeneratedImageCodec generatedImageCodec,
            VisualizationImageProfiles imageProfiles,
            StoryboardProperties properties,
            MongoTemplate mongoTemplate,
            @Qualifier("visualizationOrchestratorTaskPool") TaskPool orchestratorTaskPool
    ) {
        this.storyboardRepository = storyboardRepository;
        this.recipeTemplateRepository = recipeTemplateRepository;
        this.processRepository = processRepository;
        this.visualizationService = visualizationService;
        this.visualizationJobRepository = visualizationJobRepository;
        this.planner = planner;
        this.masterPromptWriter = masterPromptWriter;
        this.panelAttacher = panelAttacher;
        this.imageArtifactConsumer = imageArtifactConsumer;
        this.queueService = queueService;
        this.clientResolver = clientResolver;
        this.artifactService = artifactService;
        this.generatedImageCodec = generatedImageCodec;
        this.imageProfiles = imageProfiles;
        this.properties = properties;
        this.mongoTemplate = mongoTemplate;
        this.orchestratorTaskPool = orchestratorTaskPool;
    }

    /** Reads a stored storyboard image back, to cut it again on a FILL run; optional so tests can leave it out. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setImageStorageClient(ImageStorageClient imageStorageClient) {
        this.imageStorageClient = imageStorageClient;
    }

    /**
     * Starts a Visualize run in the given {@link StoryboardMode}. REPLACE plans, prompts and draws again and replaces
     * the covered steps' images. FILL reuses the latest storyboard when it still matches the recipe (no AI call: its
     * panels are cut again if needed and put back on the steps), and otherwise makes a new one that keeps the images
     * the steps already have of their own.
     */
    public RecipeStoryboardResponseDTO startJob(Long userId, Long recipeId, StoryboardMode mode) {
        return mode == StoryboardMode.REPLACE
                ? startJob(userId, recipeId, true, true, false)
                : startJob(userId, recipeId, false, false, true);
    }

    /**
     * Starts a storyboard for the recipe and returns at once; the work runs on the orchestrator pool. If one is
     * already running for the recipe, that one is returned with {@code reused=true}.
     *
     * @param regenerate plan the panels again even when the last attempt failed on the same recipe
     * @param overwriteExisting replace images the covered steps already have
     * @throws NoSuchElementException if the recipe does not exist
     * @throws RecipeAccessDeniedException if the caller does not own the recipe
     * @throws RecipeStoryboardException if the recipe has no steps or too many, or step visuals are being generated
     */
    public RecipeStoryboardResponseDTO startJob(Long userId, Long recipeId, boolean regenerate, boolean overwriteExisting) {
        return startJob(userId, recipeId, regenerate, overwriteExisting, false);
    }

    private RecipeStoryboardResponseDTO startJob(Long userId, Long recipeId, boolean regenerate, boolean overwriteExisting,
                                                 boolean reuseCompleted) {
        RecipeTemplate recipe = requireOwned(recipeId, userId);
        String activeKey = activeKey(recipeId);
        Optional<RecipeStoryboard> running = storyboardRepository.findByActiveKey(activeKey);
        if (running.isPresent()) {
            log.info("event=storyboard_job_reused reason=already_running storyboardId={} recipeId={}",
                    running.get().getId(), recipeId);
            return toDto(running.get(), true, true, false);
        }
        // Both write the steps' images; one at a time.
        if (!visualizationJobRepository.findByRecipeIdAndStatusIn(String.valueOf(recipeId),
                EnumSet.of(VisualizationJobStatus.QUEUED, VisualizationJobStatus.IN_PROGRESS)).isEmpty()) {
            throw RecipeStoryboardException.conflict(
                    "Step visuals are being generated for this recipe. Try again once they are finished.");
        }

        StoryboardSource source = loadSource(recipe);
        if (source.stepAliases().isEmpty()) {
            throw RecipeStoryboardException.unprocessable("This recipe has no steps to make a storyboard from.");
        }
        if (source.size() > properties.getMaxSourceSteps()) {
            throw RecipeStoryboardException.unprocessable("This recipe has " + source.size()
                    + " steps and checks, which is too many for one storyboard image (at most "
                    + properties.getMaxSourceSteps() + ").");
        }

        RecipeStoryboard storyboard = new RecipeStoryboard();
        storyboard.setId(UUID.randomUUID().toString());
        storyboard.setRecipeId(recipeId);
        storyboard.setUserId(userId);
        storyboard.setStatus(StoryboardStatus.QUEUED);
        storyboard.setStage(StoryboardStage.PLAN);
        storyboard.setActiveKey(activeKey);
        storyboard.setOverwriteExisting(overwriteExisting);
        storyboard.setProcessRevision(recipe.getProcessRevision());
        storyboard.setSourceHash(source.hash());
        storyboard.setTitle(recipe.getName());
        storyboard.setCreatedAt(Instant.now());
        storyboard.setUpdatedAt(Instant.now());
        RecipeStoryboard reusable = reuseCompleted ? reusableCompleted(recipeId, source) : null;
        if (reusable != null) {
            reuseFrom(storyboard, reusable);
        } else if (!regenerate) {
            continueFrom(storyboard, storyboardRepository.findFirstByRecipeIdOrderByCreatedAtDesc(recipeId).orElse(null));
        }
        try {
            storyboardRepository.insert(storyboard);
        } catch (DuplicateKeyException e) {
            // Lost a race with a concurrent start for the same recipe: join the one that won.
            Optional<RecipeStoryboard> winner = storyboardRepository.findByActiveKey(activeKey);
            if (winner.isPresent()) {
                log.info("event=storyboard_job_reused reason=concurrent_start storyboardId={} recipeId={}",
                        winner.get().getId(), recipeId);
                return toDto(winner.get(), true, true, false);
            }
            throw e;
        }
        log.debug("event=storyboard_job_queued storyboardId={} recipeId={} continued={}", storyboard.getId(), recipeId,
                !storyboard.getPanels().isEmpty());

        orchestratorTaskPool.submit(new NamedTask<>(storyboard.getId(), () -> {
            runPipeline(storyboard, source);
            return null;
        }));
        return toDto(storyboard, false, true, false);
    }

    /**
     * Carries the plan (and the image prompt) of a failed attempt on the same recipe over to a new one, so only the
     * stages that did not succeed are run again.
     */
    private static void continueFrom(RecipeStoryboard storyboard, RecipeStoryboard previous) {
        if (previous == null || previous.getStatus() != StoryboardStatus.FAILED
                || !Objects.equals(previous.getSourceHash(), storyboard.getSourceHash())
                || previous.getPanels() == null || previous.getPanels().isEmpty() || previous.getLayout() == null) {
            return;
        }
        storyboard.setTitle(previous.getTitle());
        storyboard.setDishContext(previous.getDishContext());
        storyboard.setPanels(previous.getPanels());
        storyboard.getPanels().forEach(panel -> {
            panel.setImageUrl(null);
            panel.setCrop(null);
        });
        storyboard.setOmittedSteps(previous.getOmittedSteps());
        storyboard.setLayout(previous.getLayout());
        storyboard.setWarnings(previous.getWarnings() == null ? new ArrayList<>() : new ArrayList<>(previous.getWarnings()));
        storyboard.setTextModelKey(previous.getTextModelKey());
        storyboard.setMasterPrompt(previous.getMasterPrompt());
    }

    /**
     * The latest COMPLETED storyboard when it was made from the recipe exactly as it is now and its image can be read
     * back (or is already cut): a FILL run can then reuse it without any AI call.
     */
    private RecipeStoryboard reusableCompleted(Long recipeId, StoryboardSource source) {
        RecipeStoryboard latest = storyboardRepository
                .findFirstByRecipeIdAndStatusOrderByCreatedAtDesc(recipeId, StoryboardStatus.COMPLETED).orElse(null);
        if (latest == null || !Objects.equals(latest.getSourceHash(), source.hash()) || latest.getLayout() == null
                || latest.getPanels() == null || latest.getPanels().isEmpty() || !StringUtils.hasText(latest.getImageUrl())) {
            return null;
        }
        boolean allCut = latest.getPanels().stream().allMatch(panel -> StringUtils.hasText(panel.getImageUrl()));
        return allCut || imageStorageClient != null ? latest : null;
    }

    /** Copies a completed storyboard into a new run that only cuts (if needed) and attaches. */
    private static void reuseFrom(RecipeStoryboard storyboard, RecipeStoryboard completed) {
        storyboard.setReusedFrom(completed.getId());
        storyboard.setTitle(completed.getTitle());
        storyboard.setDishContext(completed.getDishContext());
        storyboard.setPanels(completed.getPanels());
        storyboard.setOmittedSteps(completed.getOmittedSteps());
        storyboard.setLayout(completed.getLayout());
        storyboard.setWarnings(new ArrayList<>());
        storyboard.setTextModelKey(completed.getTextModelKey());
        storyboard.setMasterPrompt(completed.getMasterPrompt());
        storyboard.setImageUrl(completed.getImageUrl());
        storyboard.setImageWidth(completed.getImageWidth());
        storyboard.setImageHeight(completed.getImageHeight());
        storyboard.setImageModelKey(completed.getImageModelKey());
        storyboard.setImageModelTier(completed.getImageModelTier());
        storyboard.setUsedFallback(completed.isUsedFallback());
        storyboard.setStage(StoryboardStage.CROP);
    }

    /** The status of a storyboard generation the caller started for this recipe. */
    public RecipeStoryboardResponseDTO getJobStatus(Long userId, Long recipeId, String storyboardId) {
        RecipeStoryboard storyboard = storyboardRepository.findById(storyboardId)
                .filter(found -> Objects.equals(found.getUserId(), userId) && Objects.equals(found.getRecipeId(), recipeId))
                // A storyboard of another user or recipe is reported exactly like a missing one.
                .orElseThrow(() -> new NoSuchElementException("Storyboard not found: " + storyboardId));
        return toDto(storyboard, false, true, false);
    }

    /** The storyboard the caller has running for this recipe, so the UI can show its progress again after a reload. */
    public Optional<RecipeStoryboardResponseDTO> findActive(Long userId, Long recipeId) {
        return storyboardRepository.findByActiveKey(activeKey(recipeId))
                .filter(found -> Objects.equals(found.getUserId(), userId))
                .map(found -> toDto(found, false, true, false));
    }

    /**
     * The recipe's storyboard: its latest completed generation, with whether the recipe changed since. Readable by
     * the owner, and by anyone when the recipe is public; only the owner gets the image prompt.
     */
    public Optional<RecipeStoryboardResponseDTO> findLatest(Long userId, Long recipeId) {
        RecipeTemplate recipe = recipeTemplateRepository.findById(recipeId)
                .orElseThrow(() -> new NoSuchElementException("Recipe not found: " + recipeId));
        boolean owner = userId != null && userId.equals(recipe.getCreatedBy());
        if (!owner && recipe.getVisibility() != Visibility.PUBLIC) {
            throw new RecipeAccessDeniedException("You do not have permission to view this recipe");
        }
        return storyboardRepository.findFirstByRecipeIdAndStatusOrderByCreatedAtDesc(recipeId, StoryboardStatus.COMPLETED)
                .map(found -> toDto(found, false, owner, isOutdated(found, recipe)));
    }

    private boolean isOutdated(RecipeStoryboard storyboard, RecipeTemplate recipe) {
        try {
            return !Objects.equals(storyboard.getSourceHash(), loadSource(recipe).hash());
        } catch (RuntimeException e) {
            log.debug("event=storyboard_outdated_check_failed storyboardId={} errorType={}", storyboard.getId(),
                    e.getClass().getSimpleName());
            return false;
        }
    }

    /** The recipe as the planner reads it: its Preparation's flows, then its main process. */
    StoryboardSource loadSource(RecipeTemplate recipe) {
        List<Process> processes = new ArrayList<>(processRepository.findByRecipeId(recipe.getId()));
        // One main process: the recipe's own when several documents claim to be it.
        processes.removeIf(process -> process.getType() != ProcessType.SUBPROCESS && recipe.getMainProcessId() != null
                && !recipe.getMainProcessId().equals(process.getId()));
        processes.sort(Comparator.comparing(Process::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        List<ProcessStepPreparation> prepared = processes.stream()
                .map(process -> visualizationService.prepareStepContexts(process.getId()))
                .toList();
        return StoryboardRecipeSerializer.serialize(recipe.getName(), prepared);
    }

    static String activeKey(Long recipeId) {
        return "storyboard:" + recipeId;
    }

    // ------------------------------------------------------------------------------------------------ pipeline

    private void runPipeline(RecipeStoryboard storyboard, StoryboardSource source) {
        String id = storyboard.getId();
        // Runs on the orchestrator pool; binds the job id so every AI request of this storyboard carries it.
        OperationLog operation = OperationLog.start(log, "storyboard_job", id, "jobId=" + id
                + " recipeId=" + storyboard.getRecipeId() + " steps=" + source.stepAliases().size());
        StoryboardStage stage = StoryboardStage.PLAN;
        try {
            update(id, new Update().set("status", StoryboardStatus.IN_PROGRESS).set("startedAt", Instant.now()));

            if (storyboard.getPanels().isEmpty()) {
                enter(id, stage);
                StoryboardPlanner.Planned planned = planner.plan(storyboard.getUserId(), id, source,
                        properties.effectiveMaxPanels());
                storyboard.setTitle(StringUtils.hasText(planned.title()) ? planned.title() : storyboard.getTitle());
                storyboard.setDishContext(planned.dishContext());
                storyboard.setPanels(planned.panels());
                storyboard.setOmittedSteps(planned.omittedSteps());
                storyboard.getWarnings().addAll(planned.warnings());
                storyboard.setTextModelKey(planned.modelKey());
                StoryboardLayout grid = StoryboardLayout.of(planned.panels().size());
                RecipeStoryboard.Layout layout = new RecipeStoryboard.Layout();
                layout.setRows(grid.rows());
                layout.setColumns(grid.columns());
                storyboard.setLayout(layout);
                update(id, new Update()
                        .set("title", storyboard.getTitle())
                        .set("dishContext", storyboard.getDishContext())
                        .set("panels", storyboard.getPanels())
                        .set("omittedSteps", storyboard.getOmittedSteps())
                        .set("warnings", storyboard.getWarnings())
                        .set("textModelKey", storyboard.getTextModelKey())
                        .set("layout", layout));
            }
            StoryboardLayout grid = new StoryboardLayout(storyboard.getLayout().getRows(), storyboard.getLayout().getColumns());

            stage = StoryboardStage.PROMPT;
            if (!StringUtils.hasText(storyboard.getMasterPrompt())) {
                enter(id, stage);
                StoryboardMasterPromptWriter.Written written = masterPromptWriter.write(storyboard.getUserId(), storyboard,
                        grid, imageProfiles.scene());
                storyboard.setMasterPrompt(written.prompt());
                if (written.fromTemplate()) {
                    storyboard.getWarnings().add("The image prompt was assembled from the panels by a fixed template.");
                }
                update(id, new Update().set("masterPrompt", written.prompt()).set("warnings", storyboard.getWarnings()));
            }

            byte[] imageData = null;
            if (storyboard.getReusedFrom() == null) {
                stage = StoryboardStage.IMAGE;
                enter(id, stage);
                imageData = generateImage(storyboard).data();
            }

            stage = StoryboardStage.CROP;
            enter(id, stage);
            cutAndAttach(storyboard, source, imageData);

            update(id, new Update()
                    .set("status", StoryboardStatus.COMPLETED)
                    .set("completedAt", Instant.now())
                    .set("aiUsage", operation.aiTotals())
                    .unset("activeKey"));
            String outcome = "status=COMPLETED panels=" + storyboard.getPanels().size()
                    + " attachedSteps=" + storyboard.getStepResults().stream().filter(RecipeStoryboard.StepResult::isAttached).count()
                    + " warnings=" + storyboard.getWarnings().size();
            if (storyboard.getWarnings().isEmpty()) {
                operation.completed(outcome);
            } else {
                operation.completedWithErrors(outcome);
            }
        } catch (Exception e) {
            // Whatever goes wrong must end the job, or a polling client would wait on it forever.
            operation.failed(e, "recipeId=" + storyboard.getRecipeId() + " stage=" + stage);
            update(id, new Update()
                    .set("status", StoryboardStatus.FAILED)
                    .set("failedStage", stage)
                    .set("errorMessage", safeMessage(e))
                    .set("completedAt", Instant.now())
                    .set("aiUsage", operation.aiTotals())
                    .unset("activeKey"));
        } finally {
            operation.close();
        }
    }

    /** The one image request of a storyboard. The image is stored (and its URL recorded) before this returns. */
    private ImageGenerationClient.GeneratedImage generateImage(RecipeStoryboard storyboard) {
        String id = storyboard.getId();
        String masterPrompt = storyboard.getMasterPrompt();
        ImageGenerationClient.ImageGenerationSettings requested = new ImageGenerationClient.ImageGenerationSettings(
                properties.getImageWidth(), properties.getImageHeight(), null);
        AiArtifactSpec<ImageGenerationClient.GeneratedImage> artifactSpec = AiArtifactSpec.of(
                AiArtifactKeyBuilder.build(AiCapability.TEXT_TO_IMAGE, IMAGE_CORRELATION_TYPE, id, masterPrompt,
                        "image:" + requested.width() + "x" + requested.height()),
                generatedImageCodec, StoryboardImageArtifactConsumer.CONSUMER_ID);
        AiRequestOutcome<ImageGenerationClient.GeneratedImage> outcome = queueService.executeInline(
                storyboard.getUserId(), AiCapability.TEXT_TO_IMAGE, null, null, IMAGE_CORRELATION_TYPE, id, artifactSpec,
                selection -> clientResolver.resolveImageClient(selection.model())
                        .generate(masterPrompt, List.of(), requested.withModel(selection.model().getProviderModelId())));

        // The payload is durable from here on: a failed upload is retried from the artifact, not paid for again.
        ModelSelectionOutcome selection = outcome.selection();
        AiArtifact artifact = outcome.artifact();
        if (artifact == null) {
            artifact = new AiArtifact();
            artifact.setCorrelationId(id);
            artifact.setCorrelationType(IMAGE_CORRELATION_TYPE);
            artifact.setCapability(AiCapability.TEXT_TO_IMAGE);
            artifact.setProducedByModelKey(selection.model().getKey());
            artifact.setProducedByTier(selection.model().getTier());
            artifact.setUsedFallback(selection.usedFallback());
        }
        String imageUrl = imageArtifactConsumer.consume(artifact, outcome.value());
        artifactService.markConsumed(artifact.getId());

        storyboard.setImageUrl(imageUrl);
        storyboard.setImageModelKey(selection.model().getKey());
        storyboard.setImageModelTier(selection.model().getTier() == null ? null : selection.model().getTier().name());
        storyboard.setUsedFallback(selection.usedFallback());
        return outcome.value();
    }

    /**
     * Cuts the image into panels (when it is new, or a reused storyboard has panels without an image) and puts them
     * on the steps. A storyboard whose image cannot be cut is still a storyboard: it keeps its combined image, gets a
     * warning, and no step is changed. A cut that fails halfway leaves no panel image behind.
     *
     * @param imageData the newly drawn image, or null on a FILL run that reuses an earlier storyboard
     */
    private void cutAndAttach(RecipeStoryboard storyboard, StoryboardSource source, byte[] imageData) {
        Update update = new Update();
        boolean needsCut = imageData != null
                || storyboard.getPanels().stream().anyMatch(panel -> !StringUtils.hasText(panel.getImageUrl()));
        boolean cut = !needsCut;
        if (needsCut) {
            try {
                byte[] data = imageData != null ? imageData : readBack(storyboard.getImageUrl());
                int[] size = panelAttacher.cutPanels(storyboard, data);
                storyboard.setImageWidth(size[0]);
                storyboard.setImageHeight(size[1]);
                update.set("imageWidth", size[0]).set("imageHeight", size[1]);
                cut = true;
            } catch (Exception e) {
                FailureLogger.logFailure(log, "storyboard_panels_cut_failed", e, "storyboardId=" + storyboard.getId());
                storyboard.getPanels().forEach(panel -> {
                    panel.setImageUrl(null);
                    panel.setCrop(null);
                });
                storyboard.getWarnings().add("The storyboard could not be cut into panel images, so the steps were not given one.");
            }
        }
        if (cut) {
            storyboard.setStepResults(panelAttacher.attachToSteps(storyboard, source, storyboard.isOverwriteExisting()));
            update.set("stepResults", storyboard.getStepResults());
        }
        update.set("panels", storyboard.getPanels()).set("warnings", storyboard.getWarnings());
        update(storyboard.getId(), update);
    }

    private byte[] readBack(String imageUrl) {
        if (imageStorageClient == null || !StringUtils.hasText(imageUrl)) {
            throw new IllegalStateException("The storyboard image cannot be read back");
        }
        return imageStorageClient.download(imageUrl).data();
    }

    private void enter(String storyboardId, StoryboardStage stage) {
        update(storyboardId, new Update().set("stage", stage));
    }

    /** Every write touches {@code updatedAt}: that is what tells a live job from an abandoned one. */
    private void update(String storyboardId, Update update) {
        mongoTemplate.updateFirst(query(where("_id").is(storyboardId)), update.set("updatedAt", Instant.now()),
                RecipeStoryboard.class);
    }

    private static String safeMessage(Throwable error) {
        return error != null && StringUtils.hasText(error.getMessage())
                ? error.getMessage()
                : "The storyboard could not be generated. Please try again.";
    }

    private RecipeTemplate requireOwned(Long recipeId, Long userId) {
        RecipeTemplate recipe = recipeTemplateRepository.findById(recipeId)
                .orElseThrow(() -> new NoSuchElementException("Recipe not found: " + recipeId));
        if (userId == null || !userId.equals(recipe.getCreatedBy())) {
            throw new RecipeAccessDeniedException("You do not have permission to modify this recipe");
        }
        return recipe;
    }

    // ----------------------------------------------------------------------------------------------------- dto

    private static RecipeStoryboardResponseDTO toDto(RecipeStoryboard storyboard, boolean reused, boolean owner, boolean outdated) {
        List<RecipeStoryboard.Panel> panels = storyboard.getPanels() == null ? List.of() : storyboard.getPanels();
        return RecipeStoryboardResponseDTO.builder()
                .jobId(storyboard.getId())
                .recipeId(storyboard.getRecipeId())
                .status(storyboard.getStatus() == null ? null : storyboard.getStatus().name())
                .stage(storyboard.getStage() == null ? null : storyboard.getStage().name())
                .failedStage(storyboard.getFailedStage() == null ? null : storyboard.getFailedStage().name())
                .errorMessage(storyboard.getErrorMessage())
                .warnings(storyboard.getWarnings() == null ? List.of() : storyboard.getWarnings())
                .reused(reused)
                .outdated(outdated)
                .title(storyboard.getTitle())
                .imageUrl(storyboard.getImageUrl())
                .rows(storyboard.getLayout() == null ? null : storyboard.getLayout().getRows())
                .columns(storyboard.getLayout() == null ? null : storyboard.getLayout().getColumns())
                .panels(panels.stream().map(panel -> RecipeStoryboardResponseDTO.PanelDTO.builder()
                        .panelId(panel.getPanelId())
                        .sequence(panel.getSequence())
                        .group(panel.getGroup())
                        .component(panel.getComponent())
                        .description(panel.getDescription())
                        .sourceStepIds(panel.getSourceStepIds())
                        .imagePrompt(owner ? panel.getImagePrompt() : null)
                        .dependsOn(panel.getDependsOn())
                        .referencePanels(panel.getReferencePanels())
                        .ingredients(panel.getIngredients())
                        .equipment(panel.getEquipment())
                        .expectedState(panel.getExpectedState())
                        .imageUrl(panel.getImageUrl())
                        .build()).toList())
                .omittedSteps((storyboard.getOmittedSteps() == null ? List.<RecipeStoryboard.OmittedStep>of() : storyboard.getOmittedSteps())
                        .stream().map(step -> RecipeStoryboardResponseDTO.OmittedStepDTO.builder()
                                .stepId(step.getStepId()).reason(step.getReason()).build()).toList())
                .masterPrompt(owner ? storyboard.getMasterPrompt() : null)
                .steps((storyboard.getStepResults() == null ? List.<RecipeStoryboard.StepResult>of() : storyboard.getStepResults())
                        .stream().map(step -> RecipeStoryboardResponseDTO.StepDTO.builder()
                                .processId(step.getProcessId())
                                .stepId(step.getStepId())
                                .panelId(step.getPanelId())
                                .visualizationAssetId(step.getVisualizationAssetId())
                                .imageUrl(step.getImageUrl())
                                .imagePrompt(owner ? step.getImagePrompt() : null)
                                .attached(step.isAttached())
                                .cleared(step.isCleared())
                                .build()).toList())
                .textModelKey(owner ? storyboard.getTextModelKey() : null)
                .imageModelKey(owner ? storyboard.getImageModelKey() : null)
                .createdAt(storyboard.getCreatedAt())
                .completedAt(storyboard.getCompletedAt())
                .build();
    }
}
