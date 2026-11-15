package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAsset;
import com.processVisualisation.virtualKitchen.ai.platform.logging.OperationLog;
import com.processVisualisation.virtualKitchen.ai.recipe.RecipeProcessAiException;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.dto.VisualizationJobResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.model.VisualizationJob;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.model.VisualizationJobStatus;
import com.processVisualisation.virtualKitchen.common.concurrent.BatchResult;
import com.processVisualisation.virtualKitchen.common.concurrent.NamedTask;
import com.processVisualisation.virtualKitchen.common.concurrent.TaskPool;
import com.processVisualisation.virtualKitchen.common.concurrent.TaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Orchestrates async, per-step recipe process visualization on the {@code TaskPool} framework
 * (the bounded "visualization" worker pool plus the "orchestrator" pool, tracked in the
 * {@link VisualizationJob} collection), scoped to one process's own STEP nodes.
 */
@Service
public class RecipeProcessVisualizationJobService {

    private static final Logger log = LoggerFactory.getLogger(RecipeProcessVisualizationJobService.class);

    static final Set<VisualizationJobStatus> ACTIVE_STATUSES =
            EnumSet.of(VisualizationJobStatus.QUEUED, VisualizationJobStatus.IN_PROGRESS);

    private final RecipeProcessVisualizationService recipeProcessVisualizationService;
    private final VisualizationJobRepository visualizationJobRepository;
    private final MongoTemplate mongoTemplate;
    private final TaskPool visualizationTaskPool;
    private final TaskPool visualizationOrchestratorTaskPool;
    private final VisualizationPromptBatchService promptBatchService;
    private final VisualizationSceneAnchorService sceneAnchorService;

    public RecipeProcessVisualizationJobService(
            RecipeProcessVisualizationService recipeProcessVisualizationService,
            VisualizationJobRepository visualizationJobRepository,
            MongoTemplate mongoTemplate,
            @Qualifier("visualizationTaskPool") TaskPool visualizationTaskPool,
            @Qualifier("visualizationOrchestratorTaskPool") TaskPool visualizationOrchestratorTaskPool,
            VisualizationPromptBatchService promptBatchService,
            VisualizationSceneAnchorService sceneAnchorService
    ) {
        this.recipeProcessVisualizationService = recipeProcessVisualizationService;
        this.visualizationJobRepository = visualizationJobRepository;
        this.mongoTemplate = mongoTemplate;
        this.visualizationTaskPool = visualizationTaskPool;
        this.visualizationOrchestratorTaskPool = visualizationOrchestratorTaskPool;
        this.promptBatchService = promptBatchService;
        this.sceneAnchorService = sceneAnchorService;
    }

    /** Starts the visuals of one process, writing its image prompts in one batch first (see the 4-argument form). */
    public VisualizationJobResponseDTO startJob(Long userId, Long recipeId, Long processId) {
        return startJob(userId, recipeId, processId, List.of(processId));
    }

    /** {@link #startJob(Long, Long, Long)}, optionally regenerating images the process's steps already have. */
    public VisualizationJobResponseDTO startJob(Long userId, Long recipeId, Long processId, boolean overwriteExisting) {
        return startJob(userId, recipeId, processId, List.of(processId), overwriteExisting);
    }

    /** The 5-argument form, keeping every image the steps already have (only missing ones are drawn). */
    public VisualizationJobResponseDTO startJob(Long userId, Long recipeId, Long processId, List<Long> promptScope) {
        return startJob(userId, recipeId, processId, promptScope, false);
    }

    /**
     * Validates the process and creates a {@code QUEUED} job synchronously (so a missing process
     * fails fast, before any job exists), then hands the actual generation off to the orchestrator
     * pool and returns immediately. At most one job runs per process: if one is already
     * QUEUED/IN_PROGRESS, that job is returned with {@code reused=true} instead of starting (and
     * paying for) a duplicate whose final save would race the first one's.
     * <p>
     * Before its images, the job writes the image prompts of every process in {@code promptScope} in one text call
     * ({@link VisualizationPromptBatchService}). Jobs started together for a recipe pass the same scope, so whichever
     * runs first writes the prompts for all of them and the others find theirs already written.
     *
     * <p>
     * With {@code overwriteExisting}, the steps' stored prompts and images are forgotten first (see
     * {@link RecipeProcessVisualizationService#clearForRegeneration}), so every step gets a new prompt and image.
     * Only a job this call actually starts clears anything: joining an already-running job changes nothing.
     *
     * @param promptScope       the processes visualized together with this one (including it)
     * @param overwriteExisting regenerate and replace images the steps already have (otherwise they are kept)
     * @throws RecipeProcessAiException if the process cannot be found
     * @throws NoSuchElementException   if the process belongs to a different recipe
     */
    public VisualizationJobResponseDTO startJob(Long userId, Long recipeId, Long processId, List<Long> promptScope,
                                                boolean overwriteExisting) {
        String activeKey = activeKey(processId);
        var running = visualizationJobRepository.findByActiveKey(activeKey);
        if (running.isPresent()) {
            log.info("event=visualization_job_reused reason=already_running jobId={} recipeId={} processId={}",
                    running.get().getId(), recipeId, processId);
            return toDto(running.get(), true);
        }

        // One image per MAIN step; one per Preparation flow (see prepareVisualTargets).
        RecipeProcessVisualizationService.VisualPreparation preparation =
                recipeProcessVisualizationService.prepareVisualTargets(processId);
        Long owningRecipeId = preparation.process().getRecipeId();
        if (owningRecipeId != null && !owningRecipeId.equals(recipeId)) {
            throw new NoSuchElementException("Process " + processId + " not found in recipe " + recipeId);
        }

        VisualizationJob job = new VisualizationJob();
        job.setId(java.util.UUID.randomUUID().toString());
        job.setRecipeId(String.valueOf(recipeId));
        job.setProcessId(processId);
        job.setUserId(userId);
        job.setActiveKey(activeKey);
        job.setStatus(VisualizationJobStatus.QUEUED);
        job.setTotalSteps(preparation.targets().size());
        job.setOverwriteExisting(overwriteExisting);
        job.setCompletedSteps(0);
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        try {
            visualizationJobRepository.insert(job);
        } catch (DuplicateKeyException e) {
            // Lost a race with a concurrent start for the same process: join the job that won.
            var winner = visualizationJobRepository.findByActiveKey(activeKey);
            if (winner.isPresent()) {
                log.info("event=visualization_job_reused reason=concurrent_start jobId={} recipeId={} processId={}",
                        winner.get().getId(), recipeId, processId);
                return toDto(winner.get(), true);
            }
            throw e;
        }
        log.debug("event=visualization_job_queued jobId={} recipeId={} processId={}", job.getId(), recipeId, processId);
        if (overwriteExisting) {
            int cleared = recipeProcessVisualizationService.clearForRegeneration(recipeId, preparation.targets());
            log.info("event=visualization_regenerate jobId={} recipeId={} processId={} clearedAssets={}",
                    job.getId(), recipeId, processId, cleared);
        }

        List<Long> scope = promptScope.contains(processId) ? List.copyOf(promptScope)
                : java.util.stream.Stream.concat(promptScope.stream(), java.util.stream.Stream.of(processId)).toList();
        visualizationOrchestratorTaskPool.submit(new NamedTask<>(job.getId(), () -> {
            runPipeline(userId, recipeId, job.getId(), preparation, scope);
            return null;
        }));

        return toDto(job, false);
    }

    /**
     * Retrieves the current status and per-step results of a visualization job the caller started
     * for this recipe/process.
     *
     * @throws NoSuchElementException if no such job exists for this user, recipe and process
     */
    public VisualizationJobResponseDTO getJobStatus(Long userId, Long recipeId, Long processId, String jobId) {
        VisualizationJob job = visualizationJobRepository.findById(jobId)
                .filter(found -> Objects.equals(found.getUserId(), userId)
                        && Objects.equals(found.getRecipeId(), String.valueOf(recipeId))
                        && Objects.equals(found.getProcessId(), processId))
                // A job that belongs to another user/recipe/process is reported exactly like a missing one.
                .orElseThrow(() -> new NoSuchElementException("Process visualization job not found: " + jobId));
        return toDto(job, false);
    }

    /**
     * Every visualization the caller has QUEUED/IN_PROGRESS for one of this recipe's processes,
     * so the UI can resume showing progress after a reload.
     */
    public List<VisualizationJobResponseDTO> findActive(Long userId, Long recipeId) {
        return visualizationJobRepository
                .findByRecipeIdAndStatusIn(String.valueOf(recipeId), ACTIVE_STATUSES)
                .stream()
                .filter(job -> Objects.equals(job.getUserId(), userId))
                .map(job -> toDto(job, false))
                .toList();
    }

    static String activeKey(Long processId) {
        return "viz:" + processId;
    }

    private void runPipeline(Long userId, Long recipeId, String jobId, RecipeProcessVisualizationService.VisualPreparation preparation,
                             List<Long> promptScope) {
        // Runs on the orchestrator pool; binds the job ID before submitAll so every step task inherits it.
        OperationLog operation = OperationLog.start(log, "visualization_job", jobId, "jobId=" + jobId
                + " recipeId=" + recipeId + " processId=" + preparation.process().getId() + " steps=" + preparation.targets().size());
        markStatus(jobId, VisualizationJobStatus.IN_PROGRESS, Map.of("startedAt", Instant.now()));
        try {
            // One text call for every prompt in scope; whatever it doesn't write, each step writes for itself below.
            promptBatchService.ensurePrompts(userId, recipeId, promptScope);
            // The recipe's empty-scene image every new image is drawn against — only fetched (or made) when this job
            // still has an image to draw.
            boolean anyImageMissing = preparation.targets().stream().anyMatch(target -> !recipeProcessVisualizationService
                    .hasImage(RecipeProcessVisualizationService.visualizationKey(recipeId, target)));
            RecipeProcessVisualizationService.SceneReference scene = anyImageMissing
                    ? sceneAnchorService.ensureAnchor(userId, recipeId).orElse(null)
                    : null;

            // Read for every job: an admin can switch reference chaining (and the focused view it needs) at runtime.
            BatchResult<VisualizationAsset> batch = runTargets(userId, recipeId, jobId, preparation.targets(), scene,
                    recipeProcessVisualizationService.referenceChainEnabled());

            // Attach every asset that was actually produced, even ones with a null imageUrl —
            // generateImage() already swallows image-generation errors internally and returns such
            // an asset rather than throwing, so a null imageUrl is the common failure signal here,
            // not a thrown exception.
            Map<String, VisualizationAsset> assetsByStepId = batch.results().stream()
                    .filter(result -> result.isSuccess() && result.value() != null)
                    .collect(Collectors.toMap(TaskResult::taskId, TaskResult::value));

            recipeProcessVisualizationService.attachResultsAndSave(preparation.process(), assetsByStepId);

            boolean anyStepFailed = batch.results().stream().anyMatch(result -> !isEffectivelySuccessful(result));
            VisualizationJobStatus finalStatus = anyStepFailed
                    ? VisualizationJobStatus.COMPLETED_WITH_ERRORS
                    : VisualizationJobStatus.COMPLETED;
            markStatus(jobId, finalStatus, Map.of("completedAt", Instant.now(), "aiUsage", operation.aiTotals()));
            List<String> failedStepIds = batch.results().stream()
                    .filter(result -> !isEffectivelySuccessful(result))
                    .map(TaskResult::taskId)
                    .toList();
            String outcome = "status=" + finalStatus + " steps=" + batch.results().size()
                    + " failedSteps=" + failedStepIds.size();
            if (anyStepFailed) {
                // Each failed step's reason is logged by the step itself (visualization_image_failed / _prompt_failed).
                operation.completedWithErrors(outcome + " failedStepIds=" + failedStepIds);
            } else {
                operation.completed(outcome);
            }
        } catch (Exception e) {
            // Anything outside the per-step task boundary (e.g. the final save itself throwing)
            // must still terminate the job — otherwise a polling client would spin forever on a
            // job stuck IN_PROGRESS with no writer left to unstick it.
            operation.failed(e, "processId=" + preparation.process().getId());
            Update update = new Update()
                    .set("status", VisualizationJobStatus.FAILED)
                    .set("errorMessage", safeMessage(e))
                    .set("completedAt", Instant.now())
                    .set("updatedAt", Instant.now())
                    .set("aiUsage", operation.aiTotals())
                    .unset("activeKey");
            mongoTemplate.updateFirst(query(where("_id").is(jobId)), update, VisualizationJob.class);
        } finally {
            operation.close();
        }
    }

    /** Work run as one pool task: some targets, in order, and how to draw them. */
    private record Group(List<RecipeProcessVisualizationService.VisualTarget> targets,
                         com.processVisualisation.virtualKitchen.common.concurrent.Task<List<TaskResult<VisualizationAsset>>> run) {
    }

    /**
     * Draws every target of a job; results come back in the order of {@code targets}, each recorded as its step
     * finishes.
     * <ul>
     *   <li>{@code chained}: focused main steps that share a focus are drawn one after another, each against the
     *       image of the one before it (and the scene anchor), so the pan and the food in it carry over. Otherwise
     *       every main step is drawn on its own against the scene anchor alone.</li>
     *   <li>Each Preparation flow is one task: its steps are drawn in groups sharing one panel image
     *       ({@link #runPreparationFlow}).</li>
     *   <li>With look-alike skipping on, a main step marked as looking like the step before reuses the image of the
     *       nearest earlier main step instead of an image call, once the others are drawn.</li>
     * </ul>
     * Chains, flows and single steps run in parallel, as one pool task each.
     */
    private BatchResult<VisualizationAsset> runTargets(
            Long userId, Long recipeId, String jobId, List<RecipeProcessVisualizationService.VisualTarget> targets,
            RecipeProcessVisualizationService.SceneReference scene, boolean chained) {
        boolean skipLookAlike = recipeProcessVisualizationService.skipLookAlikeSteps();
        Map<VisualFocus, List<RecipeProcessVisualizationService.VisualTarget>> chains = new java.util.EnumMap<>(VisualFocus.class);
        Map<String, List<RecipeProcessVisualizationService.VisualTarget>> flows = new java.util.LinkedHashMap<>();
        List<Group> groups = new java.util.ArrayList<>();
        List<RecipeProcessVisualizationService.VisualTarget> lookAlikes = new java.util.ArrayList<>();
        for (RecipeProcessVisualizationService.VisualTarget target : targets) {
            if (target.isPreparationStep()) {
                List<RecipeProcessVisualizationService.VisualTarget> flow = flows.get(target.prepFlow().id());
                if (flow == null) {
                    List<RecipeProcessVisualizationService.VisualTarget> created = new java.util.ArrayList<>();
                    flows.put(target.prepFlow().id(), created);
                    groups.add(new Group(created,
                            () -> runPreparationFlow(userId, recipeId, jobId, created, scene, chained, skipLookAlike)));
                    flow = created;
                }
                flow.add(target);
                continue;
            }
            if (skipLookAlike && recipeProcessVisualizationService.marksLookAlike(recipeId, target)) {
                lookAlikes.add(target);
                continue;
            }
            VisualFocus focus = chained ? recipeProcessVisualizationService.chainFocus(recipeId, target).orElse(null) : null;
            if (focus == null) {
                List<RecipeProcessVisualizationService.VisualTarget> single = List.of(target);
                groups.add(new Group(single, () -> runChain(userId, recipeId, jobId, single, scene)));
                continue;
            }
            List<RecipeProcessVisualizationService.VisualTarget> chain = chains.get(focus);
            if (chain == null) {
                List<RecipeProcessVisualizationService.VisualTarget> created = new java.util.ArrayList<>();
                chains.put(focus, created);
                groups.add(new Group(created, () -> runChain(userId, recipeId, jobId, created, scene)));
                chain = created;
            }
            chain.add(target);
        }

        List<NamedTask<List<TaskResult<VisualizationAsset>>>> tasks = groups.stream()
                .map(group -> new NamedTask<List<TaskResult<VisualizationAsset>>>(
                        group.targets().get(0).nodeId(), group.run()))
                .toList();
        BatchResult<List<TaskResult<VisualizationAsset>>> batch = visualizationTaskPool.submitAll(tasks, null);

        Map<String, TaskResult<VisualizationAsset>> byNodeId = new java.util.HashMap<>();
        for (int i = 0; i < groups.size(); i++) {
            TaskResult<List<TaskResult<VisualizationAsset>>> groupResult = batch.results().get(i);
            if (groupResult.isSuccess() && groupResult.value() != null) {
                groupResult.value().forEach(result -> byNodeId.put(result.taskId(), result));
            }
            // A group that died outside its steps (it never should) fails the steps it did not get to.
            for (RecipeProcessVisualizationService.VisualTarget target : groups.get(i).targets()) {
                if (byNodeId.containsKey(target.nodeId())) continue;
                Throwable error = groupResult.error() != null ? groupResult.error()
                        : new IllegalStateException("Visualization chain ended before this step");
                TaskResult<VisualizationAsset> failed = TaskResult.failure(target.nodeId(), error);
                recordStepResult(jobId, failed);
                byNodeId.put(target.nodeId(), failed);
            }
        }

        // Look-alike main steps, in cooking order: each copies the nearest earlier main step that has an image.
        List<RecipeProcessVisualizationService.VisualTarget> mainSteps = targets.stream()
                .filter(target -> !target.isPreparationStep()).toList();
        for (RecipeProcessVisualizationService.VisualTarget target : lookAlikes) {
            VisualizationAsset source = null;
            for (int i = mainSteps.indexOf(target) - 1; i >= 0 && source == null; i--) {
                source = imageOf(byNodeId.get(mainSteps.get(i).nodeId()));
            }
            TaskResult<VisualizationAsset> result = reuseOrDraw(userId, recipeId, target, source, scene);
            recordStepResult(jobId, result);
            byNodeId.put(target.nodeId(), result);
        }
        return new BatchResult<>(targets.stream().map(target -> byNodeId.get(target.nodeId())).toList());
    }

    /** One chain, in cooking order; a step whose predecessor has no usable image is drawn without it. */
    private List<TaskResult<VisualizationAsset>> runChain(
            Long userId, Long recipeId, String jobId, List<RecipeProcessVisualizationService.VisualTarget> chain,
            RecipeProcessVisualizationService.SceneReference scene) {
        List<TaskResult<VisualizationAsset>> results = new java.util.ArrayList<>();
        VisualizationAsset previousAsset = null;
        for (RecipeProcessVisualizationService.VisualTarget target : chain) {
            TaskResult<VisualizationAsset> result;
            try {
                result = TaskResult.success(target.nodeId(), recipeProcessVisualizationService.resolveTarget(
                        userId, recipeId, target, scene, previousFor(recipeId, List.of(target), previousAsset)));
            } catch (Throwable t) {
                log.debug("event=task_failed taskId={} errorType={}", target.nodeId(), t.getClass().getSimpleName(), t);
                result = TaskResult.failure(target.nodeId(), t);
            }
            recordStepResult(jobId, result);
            results.add(result);
            previousAsset = result.isSuccess() ? result.value() : null;
        }
        return results;
    }

    /**
     * One Preparation flow, in cooking order. Steps that already have an image keep it. With look-alike skipping on,
     * steps marked as looking like the step before are left out and then copy the nearest earlier step of the flow
     * that has an image. The steps left to draw are split into groups of {@code preparationStepsPerImage} (4, 2 or 1,
     * see {@link RecipeProcessVisualizationService#chunkSizes}): a group of one is drawn on its own, a larger group as
     * one panel image cut into each step's image. With {@code chained}, each group is drawn against the last image of
     * the group before it.
     */
    private List<TaskResult<VisualizationAsset>> runPreparationFlow(
            Long userId, Long recipeId, String jobId, List<RecipeProcessVisualizationService.VisualTarget> flow,
            RecipeProcessVisualizationService.SceneReference scene, boolean chained, boolean skipLookAlike) {
        Map<String, TaskResult<VisualizationAsset>> byNodeId = new java.util.HashMap<>();
        List<RecipeProcessVisualizationService.VisualTarget> toDraw = new java.util.ArrayList<>();
        List<RecipeProcessVisualizationService.VisualTarget> lookAlikes = new java.util.ArrayList<>();
        for (int i = 0; i < flow.size(); i++) {
            RecipeProcessVisualizationService.VisualTarget target = flow.get(i);
            if (recipeProcessVisualizationService.hasImage(RecipeProcessVisualizationService.visualizationKey(recipeId, target))) {
                // Already drawn: resolving it makes no call and returns the stored image.
                TaskResult<VisualizationAsset> result = attempt(target,
                        () -> recipeProcessVisualizationService.resolveTarget(userId, recipeId, target, scene));
                recordStepResult(jobId, result);
                byNodeId.put(target.nodeId(), result);
            } else if (skipLookAlike && i > 0 && recipeProcessVisualizationService.marksLookAlike(recipeId, target)) {
                lookAlikes.add(target);
            } else {
                toDraw.add(target);
            }
        }

        int perImage = recipeProcessVisualizationService.preparationStepsPerImage();
        VisualizationAsset previousAsset = null;
        int start = 0;
        for (int size : RecipeProcessVisualizationService.chunkSizes(toDraw.size(), perImage)) {
            List<RecipeProcessVisualizationService.VisualTarget> chunk = toDraw.subList(start, start + size);
            start += size;
            RecipeProcessVisualizationService.PreviousStep previous;
            try {
                previous = chained ? previousFor(recipeId, chunk, previousAsset) : null;
            } catch (RuntimeException e) {
                previous = null;
            }
            List<TaskResult<VisualizationAsset>> results = drawChunk(userId, recipeId, chunk, scene, previous);
            for (TaskResult<VisualizationAsset> result : results) {
                recordStepResult(jobId, result);
                byNodeId.put(result.taskId(), result);
            }
            VisualizationAsset last = imageOf(results.get(results.size() - 1));
            previousAsset = last != null ? last : previousAsset;
        }

        for (RecipeProcessVisualizationService.VisualTarget target : lookAlikes) {
            VisualizationAsset source = null;
            for (int i = flow.indexOf(target) - 1; i >= 0 && source == null; i--) {
                source = imageOf(byNodeId.get(flow.get(i).nodeId()));
            }
            TaskResult<VisualizationAsset> result = reuseOrDraw(userId, recipeId, target, source, scene);
            recordStepResult(jobId, result);
            byNodeId.put(target.nodeId(), result);
        }
        return flow.stream().map(target -> byNodeId.get(target.nodeId())).toList();
    }

    /** One group of a Preparation flow: a single step on its own, or 2 or 4 steps as one panel image. */
    private List<TaskResult<VisualizationAsset>> drawChunk(
            Long userId, Long recipeId, List<RecipeProcessVisualizationService.VisualTarget> chunk,
            RecipeProcessVisualizationService.SceneReference scene, RecipeProcessVisualizationService.PreviousStep previous) {
        if (chunk.size() == 1) {
            RecipeProcessVisualizationService.VisualTarget target = chunk.get(0);
            return List.of(attempt(target,
                    () -> recipeProcessVisualizationService.resolveTarget(userId, recipeId, target, scene, previous)));
        }
        try {
            List<VisualizationAsset> assets = recipeProcessVisualizationService.resolveGrid(userId, recipeId, chunk, scene, previous);
            List<TaskResult<VisualizationAsset>> results = new java.util.ArrayList<>();
            for (int i = 0; i < chunk.size(); i++) {
                results.add(TaskResult.success(chunk.get(i).nodeId(), assets.get(i)));
            }
            return results;
        } catch (Throwable t) {
            log.debug("event=task_failed taskId={} errorType={}", chunk.get(0).nodeId(), t.getClass().getSimpleName(), t);
            return chunk.stream().map(target -> TaskResult.<VisualizationAsset>failure(target.nodeId(), t)).toList();
        }
    }

    /**
     * The previous image to draw {@code targets} against: {@code previousAsset}'s, read back only when the first of
     * them still has an image to draw.
     */
    private RecipeProcessVisualizationService.PreviousStep previousFor(
            Long recipeId, List<RecipeProcessVisualizationService.VisualTarget> targets, VisualizationAsset previousAsset) {
        if (previousAsset == null || recipeProcessVisualizationService.hasImage(
                RecipeProcessVisualizationService.visualizationKey(recipeId, targets.get(0)))) {
            return null;
        }
        return sceneAnchorService.readStepImage(previousAsset).orElse(null);
    }

    /** A look-alike step copies {@code source}'s image; without one (it failed or is missing) it is drawn itself. */
    private TaskResult<VisualizationAsset> reuseOrDraw(
            Long userId, Long recipeId, RecipeProcessVisualizationService.VisualTarget target, VisualizationAsset source,
            RecipeProcessVisualizationService.SceneReference scene) {
        return attempt(target, () -> source != null
                ? recipeProcessVisualizationService.reuseImage(recipeId, target, source)
                : recipeProcessVisualizationService.resolveTarget(userId, recipeId, target, scene));
    }

    private TaskResult<VisualizationAsset> attempt(RecipeProcessVisualizationService.VisualTarget target,
                                                   java.util.function.Supplier<VisualizationAsset> work) {
        try {
            return TaskResult.success(target.nodeId(), work.get());
        } catch (Throwable t) {
            log.debug("event=task_failed taskId={} errorType={}", target.nodeId(), t.getClass().getSimpleName(), t);
            return TaskResult.failure(target.nodeId(), t);
        }
    }

    /** The asset of a result that has an image, else null. */
    private static VisualizationAsset imageOf(TaskResult<VisualizationAsset> result) {
        return result != null && result.isSuccess() && result.value() != null
                && result.value().getImageUrl() != null && !result.value().getImageUrl().isBlank()
                ? result.value() : null;
    }

    private void recordStepResult(String jobId, TaskResult<VisualizationAsset> result) {
        VisualizationJob.StepResult stepResult = new VisualizationJob.StepResult();
        stepResult.setStepId(result.taskId());
        boolean success = isEffectivelySuccessful(result);
        stepResult.setSuccess(success);
        if (result.isSuccess() && result.value() != null) {
            VisualizationAsset asset = result.value();
            stepResult.setVisualizationAssetId(asset.getId());
            stepResult.setImageUrl(asset.getImageUrl());
            stepResult.setModelKey(asset.getResolvedModelKey());
            stepResult.setTier(asset.getResolvedTier() != null ? asset.getResolvedTier().name() : null);
            stepResult.setUsedFallback(asset.isUsedFallback());
            if (!success) {
                stepResult.setErrorMessage(asset.getImageFailureReason() != null
                        ? asset.getImageFailureReason()
                        : "Image generation failed for this step");
            }
        } else {
            stepResult.setErrorMessage(safeMessage(result.error()));
        }

        Update update = new Update()
                .push("stepResults", stepResult)
                .inc("completedSteps", 1)
                .set("updatedAt", Instant.now());
        mongoTemplate.updateFirst(query(where("_id").is(jobId)), update, VisualizationJob.class);
    }

    private boolean isEffectivelySuccessful(TaskResult<VisualizationAsset> result) {
        return result.isSuccess() && result.value() != null && result.value().getImageUrl() != null;
    }

    private void markStatus(String jobId, VisualizationJobStatus status, Map<String, Object> extraFields) {
        Update update = new Update().set("status", status).set("updatedAt", Instant.now());
        extraFields.forEach(update::set);
        if (!ACTIVE_STATUSES.contains(status)) {
            update.unset("activeKey");
        }
        mongoTemplate.updateFirst(query(where("_id").is(jobId)), update, VisualizationJob.class);
    }

    private String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message != null ? message : t.getClass().getSimpleName();
    }

    private VisualizationJobResponseDTO toDto(VisualizationJob job, boolean reused) {
        List<VisualizationJobResponseDTO.StepResultDTO> steps = job.getStepResults().stream()
                .map(sr -> VisualizationJobResponseDTO.StepResultDTO.builder()
                        .stepId(sr.getStepId())
                        .success(sr.isSuccess())
                        .visualizationAssetId(sr.getVisualizationAssetId())
                        .imageUrl(sr.getImageUrl())
                        .errorMessage(sr.getErrorMessage())
                        .modelKey(sr.getModelKey())
                        .tier(sr.getTier())
                        .usedFallback(sr.isUsedFallback())
                        .build())
                .toList();

        return VisualizationJobResponseDTO.builder()
                .jobId(job.getId())
                .recipeId(job.getRecipeId())
                .processId(job.getProcessId())
                .status(job.getStatus() != null ? job.getStatus().name() : null)
                .totalSteps(job.getTotalSteps())
                .completedSteps(job.getCompletedSteps())
                .steps(steps)
                .errorMessage(job.getErrorMessage())
                .reused(reused)
                .build();
    }
}
