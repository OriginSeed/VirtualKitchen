package com.processVisualisation.virtualKitchen.ai.recipe.workflow;

import com.processVisualisation.virtualKitchen.ai.platform.analytics.AiAnalyticsRecorder;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessGenerationJobRepository;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessGenerationJobService;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.RecipeProcessGenerationJobResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.RecipeProcessGenerationRequestDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.model.RecipeProcessGenerationJob;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.StepNarrationService;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.dto.StepNarrationResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationJobService;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationJobRepository;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.dto.VisualizationJobResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.model.VisualizationJob;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.RecipeAiWorkflowStatusResolver.Resolution;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto.RecipeAiTaskSelectionDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto.RecipeAiWorkflowCreateRequestDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto.RecipeAiWorkflowEstimateDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto.RecipeAiWorkflowResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiWorkflow;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiWorkflow.ApprovedProcess;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiWorkflow.ApprovedSnapshot;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiWorkflow.VisualizationJobRef;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiWorkflowStatus;
import com.processVisualisation.virtualKitchen.common.logging.FailureLogger;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeRevisionConflictException;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import com.processVisualisation.virtualKitchen.recipe.repository.ProcessRepository;
import com.processVisualisation.virtualKitchen.recipe.repository.RecipeTemplateRepository;
import com.processVisualisation.virtualKitchen.recipe.validation.RecipeReadinessEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * AI Recipe Creation: orchestrates the existing specialised AI services into one user-controlled
 * workflow.
 * <pre>
 *   create ──► RecipeProcessGenerationJobService (PROCESS)          ── result loaded into the editor,
 *                         │                                            the user reviews / edits it
 *                         ▼
 *                 WAITING_FOR_APPROVAL ──approve(saved revision)──┬──► RecipeProcessVisualizationJobService (VISUALS)
 *                                                                 └──► StepNarrationService               (NARRATION)
 * </pre>
 * Approval is the gate: nothing that spends image or TTS credits starts before it, and what starts
 * after it reads the recipe exactly as saved at the approved revision. VISUALS and NARRATION are
 * submitted to their own pools and never wait for each other; credits, model routing, retries and
 * admission limits stay where they always were, inside {@code AiRequestQueueService}.
 * <p>
 * This class does no AI work and runs no threads of its own. Task progress is derived from the
 * underlying jobs on every read ({@link RecipeAiWorkflowStatusResolver}); the workflow record only
 * stores what the jobs can't know — the selection, the approval and the outcome.
 */
@Service
public class RecipeAiWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(RecipeAiWorkflowService.class);

    /** How long a finished, never-dismissed workflow is still shown when its recipe reopens. */
    static final Duration FINISHED_WORKFLOW_TTL = Duration.ofHours(24);

    private final RecipeAiWorkflowRepository workflowRepository;
    private final MongoTemplate mongoTemplate;
    private final RecipeTemplateRepository recipeTemplateRepository;
    private final ProcessRepository processRepository;
    private final RecipeProcessVisualizationService stepSource;
    private final RecipeProcessGenerationJobService generationJobService;
    private final RecipeProcessGenerationJobRepository generationJobRepository;
    private final RecipeProcessVisualizationJobService visualizationJobService;
    private final VisualizationJobRepository visualizationJobRepository;
    private final StepNarrationService narrationService;
    private final RecipeAiWorkflowEstimator estimator;
    private final RecipeAiWorkflowStatusResolver resolver;
    private final RecipeReadinessEvaluator readinessEvaluator = new RecipeReadinessEvaluator();
    private final Clock clock;

    @Autowired
    public RecipeAiWorkflowService(
            RecipeAiWorkflowRepository workflowRepository,
            MongoTemplate mongoTemplate,
            RecipeTemplateRepository recipeTemplateRepository,
            ProcessRepository processRepository,
            RecipeProcessVisualizationService stepSource,
            RecipeProcessGenerationJobService generationJobService,
            RecipeProcessGenerationJobRepository generationJobRepository,
            RecipeProcessVisualizationJobService visualizationJobService,
            VisualizationJobRepository visualizationJobRepository,
            StepNarrationService narrationService,
            RecipeAiWorkflowEstimator estimator,
            RecipeAiWorkflowStatusResolver resolver) {
        this(workflowRepository, mongoTemplate, recipeTemplateRepository, processRepository, stepSource,
                generationJobService, generationJobRepository, visualizationJobService, visualizationJobRepository,
                narrationService, estimator, resolver, Clock.systemUTC());
    }

    RecipeAiWorkflowService(
            RecipeAiWorkflowRepository workflowRepository,
            MongoTemplate mongoTemplate,
            RecipeTemplateRepository recipeTemplateRepository,
            ProcessRepository processRepository,
            RecipeProcessVisualizationService stepSource,
            RecipeProcessGenerationJobService generationJobService,
            RecipeProcessGenerationJobRepository generationJobRepository,
            RecipeProcessVisualizationJobService visualizationJobService,
            VisualizationJobRepository visualizationJobRepository,
            StepNarrationService narrationService,
            RecipeAiWorkflowEstimator estimator,
            RecipeAiWorkflowStatusResolver resolver,
            Clock clock) {
        this.workflowRepository = workflowRepository;
        this.mongoTemplate = mongoTemplate;
        this.recipeTemplateRepository = recipeTemplateRepository;
        this.processRepository = processRepository;
        this.stepSource = stepSource;
        this.generationJobService = generationJobService;
        this.generationJobRepository = generationJobRepository;
        this.visualizationJobService = visualizationJobService;
        this.visualizationJobRepository = visualizationJobRepository;
        this.narrationService = narrationService;
        this.estimator = estimator;
        this.resolver = resolver;
        this.clock = clock;
    }

    // --- estimate ------------------------------------------------------------------------------

    /**
     * Approximate credits for a selection, before anything is created. With PROCESS selected the
     * step count is an assumed range; otherwise it is the recipe's saved steps that still need work.
     */
    public RecipeAiWorkflowEstimateDTO estimate(Long userId, Long recipeId, RecipeAiTaskSelectionDTO selection) {
        requireOwned(recipeId, userId);
        List<RecipeAiTaskType> tasks = RecipeAiTaskSelection.normalize(selection);
        RecipeAiWorkflowEstimator.StepCounts steps = tasks.contains(RecipeAiTaskType.PROCESS)
                ? estimator.assumedStepCounts()
                : remainingStepCounts(userId, recipeId, savedSteps(recipeId));
        return estimator.estimate(tasks, steps);
    }

    /**
     * What approving this workflow would cost now: its downstream tasks priced against the recipe's
     * currently saved steps (so edits made during review are reflected).
     */
    public RecipeAiWorkflowEstimateDTO estimateApproval(Long userId, Long recipeId, String workflowId) {
        RecipeAiWorkflow workflow = findOwned(userId, recipeId, workflowId);
        List<RecipeAiTaskType> downstream = workflow.getSelectedTasks().stream().filter(RecipeAiTaskType::isDownstream).toList();
        return estimator.estimate(downstream, remainingStepCounts(userId, recipeId, savedSteps(recipeId)));
    }

    // --- create --------------------------------------------------------------------------------

    /**
     * Starts AI Recipe Creation. With PROCESS selected this starts the existing process generation
     * job (its result is loaded into the editor by the frontend, as before) and the workflow then
     * waits for approval; without it, the workflow waits for approval of the existing process right
     * away. Downstream tasks never start here. A recipe has at most one open workflow: if one is
     * open, it is returned with {@code reused=true}.
     */
    public RecipeAiWorkflowResponseDTO create(Long userId, Long recipeId, RecipeAiWorkflowCreateRequestDTO request) {
        requireOwned(recipeId, userId);
        List<RecipeAiTaskType> tasks = RecipeAiTaskSelection.normalize(request.getSelection());

        Optional<RecipeAiWorkflow> open = findOpen(userId, recipeId);
        if (open.isPresent()) {
            log.info("event=ai_workflow_reused workflowId={} recipeId={}", open.get().getId(), recipeId);
            return toDto(open.get(), resolve(userId, open.get()), true, null);
        }

        boolean needsExistingSteps = !tasks.contains(RecipeAiTaskType.PROCESS);
        RecipeAiTaskSelection.validate(tasks, request.getRecipeText(),
                needsExistingSteps && savedSteps(recipeId).values().stream().anyMatch(steps -> !steps.isEmpty()));

        Instant now = now();
        RecipeAiWorkflow workflow = new RecipeAiWorkflow();
        workflow.setId(UUID.randomUUID().toString());
        workflow.setRecipeId(recipeId);
        workflow.setUserId(userId);
        workflow.setSelectedTasks(new ArrayList<>(tasks));
        workflow.setRecipeText(tasks.contains(RecipeAiTaskType.PROCESS) ? request.getRecipeText().trim() : null);
        workflow.setStatus(RecipeAiWorkflowStatus.CREATED);
        workflow.setActiveKey(activeKey(recipeId));
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        try {
            workflowRepository.insert(workflow);
        } catch (DuplicateKeyException e) {
            // Lost a race with a concurrent start for the same recipe: join the workflow that won.
            RecipeAiWorkflow winner = workflowRepository.findByActiveKey(activeKey(recipeId)).orElseThrow(() -> e);
            return toDto(winner, resolve(userId, winner), true, null);
        }

        RecipeProcessGenerationJobResponseDTO generation = null;
        if (tasks.contains(RecipeAiTaskType.PROCESS)) {
            try {
                generation = startGeneration(userId, workflow);
            } catch (RuntimeException e) {
                // Nothing was started: close the workflow so the recipe isn't blocked by it.
                mongoTemplate.updateFirst(query(where("_id").is(workflow.getId())), new Update()
                        .set("status", RecipeAiWorkflowStatus.FAILED).set("completedAt", now()).set("updatedAt", now())
                        .set("dismissedAt", now()).unset("activeKey"), RecipeAiWorkflow.class);
                throw e;
            }
            workflow.setGenerationJobId(generation.getJobId());
            workflow.setStatus(RecipeAiWorkflowStatus.GENERATING_PROCESS);
        } else {
            workflow.setStatus(RecipeAiWorkflowStatus.WAITING_FOR_APPROVAL);
        }
        Update update = new Update().set("status", workflow.getStatus()).set("updatedAt", now());
        if (workflow.getGenerationJobId() != null) {
            update.set("generationJobId", workflow.getGenerationJobId());
        }
        mongoTemplate.updateFirst(query(where("_id").is(workflow.getId())), update, RecipeAiWorkflow.class);
        log.info("event=ai_workflow_created workflowId={} recipeId={} tasks={} generationJobId={}",
                workflow.getId(), recipeId, tasks, workflow.getGenerationJobId());
        return toDto(workflow, resolve(userId, workflow), false, generation);
    }

    // --- approve -------------------------------------------------------------------------------

    /**
     * The approval gate. Requires the generated process to have reached the editor and the recipe's
     * saved revision to equal {@code approvedRevision} — the revision the editor saved right before
     * the user approved — so the downstream tasks read exactly what the user approved. Records the
     * approved steps, then starts the selected downstream tasks independently.
     *
     * @throws RecipeRevisionConflictException (409) if the recipe was saved again since
     * @throws RecipeAiWorkflowException       (409) if the workflow isn't waiting for approval
     */
    public RecipeAiWorkflowResponseDTO approve(Long userId, Long recipeId, String workflowId, Long approvedRevision) {
        RecipeTemplate recipe = requireOwned(recipeId, userId);
        RecipeAiWorkflow workflow = findOwned(userId, recipeId, workflowId);
        Resolution current = resolve(userId, workflow);
        if (current.status() != RecipeAiWorkflowStatus.WAITING_FOR_APPROVAL) {
            throw RecipeAiWorkflowException.conflict("This AI Recipe Creation is not waiting for approval");
        }
        if (workflow.isSelected(RecipeAiTaskType.PROCESS)) {
            RecipeProcessGenerationJob generation = generationJob(workflow);
            if (generation == null || generation.getResultAppliedAt() == null) {
                throw RecipeAiWorkflowException.conflict("The generated recipe process hasn't been loaded into the editor yet");
            }
        }
        long savedRevision = recipe.getProcessRevision() != null ? recipe.getProcessRevision() : 0L;
        if (approvedRevision == null || savedRevision != approvedRevision) {
            throw new RecipeRevisionConflictException(
                    "The recipe changed since you approved it. Let it save, then approve again.");
        }

        Map<Long, List<String>> steps = savedSteps(recipeId);
        List<ApprovedProcess> approvedProcesses = new ArrayList<>();
        Map<Long, String> names = processNames(recipeId);
        steps.forEach((processId, stepIds) -> {
            if (!stepIds.isEmpty()) {
                approvedProcesses.add(new ApprovedProcess(processId, names.get(processId), stepIds));
            }
        });
        boolean hasDownstream = workflow.getSelectedTasks().stream().anyMatch(RecipeAiTaskType::isDownstream);
        if (hasDownstream && approvedProcesses.isEmpty()) {
            throw RecipeAiWorkflowException.conflict("The recipe process has no steps to create visuals or narration for");
        }
        // Visuals and narration for the whole recipe need its whole structure (preparations used by one connected
        // main process); the editor shows the same blockers before the user can approve.
        if (hasDownstream) {
            RecipeReadinessEvaluator.Readiness readiness = readinessEvaluator.evaluate(processRepository.findByRecipeId(recipeId));
            if (!readiness.ready()) throw RecipeAiWorkflowException.unprocessable(RecipeReadinessEvaluator.summarize(readiness));
        }
        ApprovedSnapshot snapshot = new ApprovedSnapshot(savedRevision, now(), approvedProcesses);

        // Atomic gate: exactly one approval wins, even if the button is pressed twice.
        RecipeAiWorkflow approved = mongoTemplate.findAndModify(
                query(where("_id").is(workflowId).and("approvedSnapshot").is(null)
                        .and("status").ne(RecipeAiWorkflowStatus.CANCELLED)),
                new Update().set("approvedSnapshot", snapshot)
                        .set("status", RecipeAiWorkflowStatus.RUNNING_DOWNSTREAM_TASKS)
                        .set("updatedAt", now()),
                FindAndModifyOptions.options().returnNew(true),
                RecipeAiWorkflow.class);
        if (approved == null) {
            throw RecipeAiWorkflowException.conflict("This AI Recipe Creation was already approved or discarded");
        }
        log.info("event=ai_workflow_approved workflowId={} recipeId={} revision={} processes={} steps={}",
                workflowId, recipeId, savedRevision, approvedProcesses.size(),
                approvedProcesses.stream().mapToInt(process -> process.getStepIds().size()).sum());

        // Both only submit work to their own pools and return: neither waits for the other.
        if (approved.isSelected(RecipeAiTaskType.VISUALS)) {
            approved.setVisualizationJobs(startVisuals(userId, recipeId, workflow.getId(), approvedProcesses.stream()
                    .map(ApprovedProcess::getProcessId).toList(), approved.getVisualizationJobs()));
            mongoTemplate.updateFirst(query(where("_id").is(workflowId)),
                    new Update().set("visualizationJobs", approved.getVisualizationJobs()).set("updatedAt", now()),
                    RecipeAiWorkflow.class);
        }
        if (approved.isSelected(RecipeAiTaskType.NARRATION)) {
            for (ApprovedProcess process : approvedProcesses) {
                startNarration(userId, recipeId, workflow.getId(), process.getProcessId(), process.getStepIds());
            }
        }
        return toDto(approved, resolve(userId, approved), false, null);
    }

    // --- retry ---------------------------------------------------------------------------------

    /**
     * Retries one failed task, and only its failed part: a failed process generation is started
     * again from the stored recipe text; visuals restart only for processes whose job had failures
     * (steps that already have an image are reused by the visualization service, not regenerated);
     * narration is re-requested only for its failed steps (READY narration is never regenerated).
     */
    public RecipeAiWorkflowResponseDTO retryTask(Long userId, Long recipeId, String workflowId, RecipeAiTaskType task) {
        requireOwned(recipeId, userId);
        RecipeAiWorkflow workflow = findOwned(userId, recipeId, workflowId);
        Resolution current = resolve(userId, workflow);
        RecipeAiWorkflowResponseDTO.TaskDTO taskState = current.task(task);
        if (!workflow.isSelected(task) || !taskState.isRetryable()) {
            throw RecipeAiWorkflowException.conflict("Only a failed task can be retried");
        }
        if (task == RecipeAiTaskType.NARRATION && taskState.getRetryAfter() != null && taskState.getRetryAfter().isAfter(now())) {
            throw RecipeAiWorkflowException.conflict("Narration failed moments ago; try again in a few seconds");
        }
        reopen(workflow);

        RecipeProcessGenerationJobResponseDTO generation = null;
        Update update = new Update().set("updatedAt", now()).unset("completedAt");
        switch (task) {
            case PROCESS -> {
                generation = startGeneration(userId, workflow);
                workflow.setGenerationJobId(generation.getJobId());
                workflow.setStatus(RecipeAiWorkflowStatus.GENERATING_PROCESS);
                update.set("generationJobId", generation.getJobId()).set("status", RecipeAiWorkflowStatus.GENERATING_PROCESS);
            }
            case VISUALS -> {
                Map<String, VisualizationJob> jobs = visualizationJobs(workflow);
                List<Long> toRestart = workflow.getVisualizationJobs().stream()
                        .filter(ref -> needsVisualsRetry(jobs.get(ref.getJobId())))
                        .map(VisualizationJobRef::getProcessId)
                        .toList();
                workflow.setVisualizationJobs(startVisuals(userId, recipeId, workflow.getId(), toRestart, workflow.getVisualizationJobs()));
                workflow.setStatus(RecipeAiWorkflowStatus.RUNNING_DOWNSTREAM_TASKS);
                update.set("visualizationJobs", workflow.getVisualizationJobs()).set("status", workflow.getStatus());
            }
            case NARRATION -> {
                Map<String, StepNarrationResponseDTO> narrations = narrations(userId, workflow);
                for (ApprovedProcess process : workflow.getApprovedSnapshot().getProcesses()) {
                    List<String> failed = process.getStepIds().stream()
                            .filter(stepId -> isFailedNarration(narrations.get(stepId)))
                            .toList();
                    if (!failed.isEmpty()) {
                        startNarration(userId, recipeId, workflow.getId(), process.getProcessId(), failed);
                    }
                }
                workflow.setStatus(RecipeAiWorkflowStatus.RUNNING_DOWNSTREAM_TASKS);
                update.set("status", workflow.getStatus());
            }
        }
        mongoTemplate.updateFirst(query(where("_id").is(workflowId)), update, RecipeAiWorkflow.class);
        log.info("event=ai_workflow_task_retried workflowId={} recipeId={} task={}", workflowId, recipeId, task);
        return toDto(workflow, resolve(userId, workflow), false, generation);
    }

    // --- discard / dismiss / read ----------------------------------------------------------------

    /**
     * Discards a workflow that is waiting for approval. This is the only cancellation offered: no
     * AI work is running at that point, so nothing keeps going behind the user's back. Running AI
     * calls can't be interrupted, so a workflow that is generating or past approval can't be discarded.
     */
    public RecipeAiWorkflowResponseDTO discard(Long userId, Long recipeId, String workflowId) {
        requireOwned(recipeId, userId);
        RecipeAiWorkflow workflow = findOwned(userId, recipeId, workflowId);
        if (!resolve(userId, workflow).cancellable()) {
            throw RecipeAiWorkflowException.conflict("Running AI tasks can't be cancelled; they will finish on their own");
        }
        RecipeAiWorkflow discarded = mongoTemplate.findAndModify(
                query(where("_id").is(workflowId).and("approvedSnapshot").is(null)),
                new Update().set("status", RecipeAiWorkflowStatus.CANCELLED).set("completedAt", now())
                        .set("updatedAt", now()).unset("activeKey"),
                FindAndModifyOptions.options().returnNew(true),
                RecipeAiWorkflow.class);
        if (discarded == null) {
            throw RecipeAiWorkflowException.conflict("This AI Recipe Creation was already approved");
        }
        log.info("event=ai_workflow_discarded workflowId={} recipeId={}", workflowId, recipeId);
        return toDto(discarded, resolve(userId, discarded), false, null);
    }

    /** Hides a finished workflow's summary, so it isn't shown again when the recipe reopens. */
    public void dismiss(Long userId, Long recipeId, String workflowId) {
        RecipeAiWorkflow workflow = findOwned(userId, recipeId, workflowId);
        if (!refresh(userId, workflow).status().isTerminal()) {
            throw RecipeAiWorkflowException.conflict("Only a finished AI Recipe Creation can be dismissed");
        }
        mongoTemplate.updateFirst(query(where("_id").is(workflowId)),
                new Update().set("dismissedAt", now()).set("updatedAt", now()), RecipeAiWorkflow.class);
    }

    public RecipeAiWorkflowResponseDTO getStatus(Long userId, Long recipeId, String workflowId) {
        RecipeAiWorkflow workflow = findOwned(userId, recipeId, workflowId);
        return toDto(workflow, refresh(userId, workflow), false, null);
    }

    /**
     * The workflow the editor should attach to when the recipe opens: the open one, else the newest
     * recently finished one the user hasn't dismissed (so its outcome and retry buttons are shown).
     */
    public Optional<RecipeAiWorkflowResponseDTO> findCurrent(Long userId, Long recipeId) {
        Optional<RecipeAiWorkflow> workflow = workflowRepository.findByActiveKey(activeKey(recipeId))
                .filter(found -> Objects.equals(found.getUserId(), userId));
        if (workflow.isEmpty()) {
            Instant cutoff = now().minus(FINISHED_WORKFLOW_TTL);
            workflow = workflowRepository.findFirstByRecipeIdAndUserIdAndDismissedAtIsNullOrderByCreatedAtDesc(recipeId, userId)
                    .filter(found -> found.getCompletedAt() == null || found.getCompletedAt().isAfter(cutoff))
                    .filter(found -> found.getStatus() != RecipeAiWorkflowStatus.CANCELLED);
        }
        return workflow.map(found -> toDto(found, refresh(userId, found), false, null));
    }

    // --- internals -----------------------------------------------------------------------------

    private RecipeProcessGenerationJobResponseDTO startGeneration(Long userId, RecipeAiWorkflow workflow) {
        // A per-attempt idempotency key, so a retry is a new generation but a double submit isn't.
        String clientRequestId = "wf-" + workflow.getId() + "-" + UUID.randomUUID();
        RecipeProcessGenerationJobResponseDTO job;
        // Tags the job's AI requests with this workflow (the MDC travels with the job onto its pool threads).
        try (MDC.MDCCloseable ignored = MDC.putCloseable(AiAnalyticsRecorder.WORKFLOW_ID_MDC, workflow.getId())) {
            job = generationJobService.startJob(userId, workflow.getRecipeId(),
                    new RecipeProcessGenerationRequestDTO(workflow.getRecipeText(), clientRequestId));
        }
        if (job.isReused() && "EDIT".equals(job.getMode())) {
            throw RecipeAiWorkflowException.conflict("An AI edit is still running for this recipe. Try again once it finishes.");
        }
        return job;
    }

    /**
     * Starts (or joins) a visualization job per process. A process whose job can't be started is
     * recorded without a job id, so its steps count as failed and a retry starts it again.
     */
    private List<VisualizationJobRef> startVisuals(Long userId, Long recipeId, String workflowId, List<Long> processIds,
                                                   List<VisualizationJobRef> existing) {
        Map<Long, VisualizationJobRef> refs = new LinkedHashMap<>();
        for (VisualizationJobRef ref : existing) {
            refs.put(ref.getProcessId(), ref);
        }
        for (Long processId : processIds) {
            String jobId = null;
            try {
                try (MDC.MDCCloseable ignored = MDC.putCloseable(AiAnalyticsRecorder.WORKFLOW_ID_MDC, workflowId)) {
                    // Same prompt scope for every process started here: their image prompts are written in one call.
                    jobId = visualizationJobService.startJob(userId, recipeId, processId, processIds).getJobId();
                }
            } catch (RuntimeException e) {
                FailureLogger.logFailure(log, "ai_workflow_visuals_start_failed", e,
                        "recipeId=" + recipeId + " processId=" + processId);
            }
            refs.put(processId, new VisualizationJobRef(processId, jobId));
        }
        return new ArrayList<>(refs.values());
    }

    private void startNarration(Long userId, Long recipeId, String workflowId, Long processId, List<String> stepIds) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable(AiAnalyticsRecorder.WORKFLOW_ID_MDC, workflowId)) {
            narrationService.ensureAll(userId, recipeId, processId, stepIds);
        } catch (RuntimeException e) {
            // The steps stay NOT_GENERATED and are reported as failed, so the user can retry them.
            FailureLogger.logFailure(log, "ai_workflow_narration_start_failed", e,
                    "recipeId=" + recipeId + " processId=" + processId);
        }
    }

    private static boolean needsVisualsRetry(VisualizationJob job) {
        if (job == null) {
            return true;
        }
        return switch (job.getStatus()) {
            case FAILED, COMPLETED_WITH_ERRORS -> true;
            default -> false;
        };
    }

    private static boolean isFailedNarration(StepNarrationResponseDTO narration) {
        return narration != null && narration.isNarratable()
                && ("FAILED".equals(narration.getStatus()) || "NOT_GENERATED".equals(narration.getStatus()));
    }

    /** Re-acquires the recipe's open-workflow slot for a retry of a finished workflow. */
    private void reopen(RecipeAiWorkflow workflow) {
        if (workflow.getActiveKey() != null) {
            return;
        }
        String key = activeKey(workflow.getRecipeId());
        try {
            mongoTemplate.updateFirst(query(where("_id").is(workflow.getId())),
                    new Update().set("activeKey", key).unset("dismissedAt"), RecipeAiWorkflow.class);
        } catch (DuplicateKeyException e) {
            throw RecipeAiWorkflowException.conflict("Another AI Recipe Creation is in progress for this recipe");
        }
        workflow.setActiveKey(key);
    }

    /**
     * Resolves the workflow and records a status change (moving to approval, reaching the end) on
     * the stored record. The write is conditional on the status it was read with, so it can never
     * undo a concurrent approval or discard.
     */
    private Resolution refresh(Long userId, RecipeAiWorkflow workflow) {
        Resolution resolution = resolve(userId, workflow);
        RecipeAiWorkflowStatus previous = workflow.getStatus();
        if (resolution.status() != previous) {
            Update update = new Update().set("status", resolution.status()).set("updatedAt", now());
            if (resolution.status().isTerminal()) {
                update.set("completedAt", now()).unset("activeKey");
            }
            mongoTemplate.updateFirst(query(where("_id").is(workflow.getId()).and("status").is(previous)),
                    update, RecipeAiWorkflow.class);
            log.info("event=ai_workflow_status_changed workflowId={} from={} to={}",
                    workflow.getId(), previous, resolution.status());
            workflow.setStatus(resolution.status());
        }
        return resolution;
    }

    private Resolution resolve(Long userId, RecipeAiWorkflow workflow) {
        boolean approved = workflow.getApprovedSnapshot() != null;
        return resolver.resolve(new RecipeAiWorkflowStatusResolver.Inputs(
                workflow,
                generationJob(workflow),
                approved && workflow.isSelected(RecipeAiTaskType.VISUALS) ? visualizationJobs(workflow) : Map.of(),
                approved && workflow.isSelected(RecipeAiTaskType.NARRATION) ? narrations(userId, workflow) : Map.of(),
                now()));
    }

    private Optional<RecipeAiWorkflow> findOpen(Long userId, Long recipeId) {
        Optional<RecipeAiWorkflow> open = workflowRepository.findByActiveKey(activeKey(recipeId));
        if (open.isEmpty()) {
            return open;
        }
        // An open workflow that has in fact finished is closed here instead of blocking a new one.
        return refresh(userId, open.get()).status().isTerminal() ? Optional.empty() : open;
    }

    private RecipeProcessGenerationJob generationJob(RecipeAiWorkflow workflow) {
        return workflow.getGenerationJobId() == null ? null
                : generationJobRepository.findById(workflow.getGenerationJobId()).orElse(null);
    }

    private Map<String, VisualizationJob> visualizationJobs(RecipeAiWorkflow workflow) {
        List<String> ids = workflow.getVisualizationJobs().stream()
                .map(VisualizationJobRef::getJobId).filter(Objects::nonNull).toList();
        Map<String, VisualizationJob> jobs = new HashMap<>();
        if (!ids.isEmpty()) {
            visualizationJobRepository.findAllById(ids).forEach(job -> jobs.put(job.getId(), job));
        }
        return jobs;
    }

    /** Narration state of the approved steps that still exist, by step id. */
    private Map<String, StepNarrationResponseDTO> narrations(Long userId, RecipeAiWorkflow workflow) {
        Map<String, StepNarrationResponseDTO> byStep = new HashMap<>();
        for (ApprovedProcess process : workflow.getApprovedSnapshot().getProcesses()) {
            Set<String> approvedIds = new HashSet<>(process.getStepIds());
            try {
                narrationService.list(userId, workflow.getRecipeId(), process.getProcessId()).stream()
                        .filter(narration -> approvedIds.contains(narration.getStepId()))
                        .forEach(narration -> byStep.put(narration.getStepId(), narration));
            } catch (NoSuchElementException e) {
                // The process was deleted after approval: its steps no longer count.
            }
        }
        return byStep;
    }

    /** STEP node ids per saved process of the recipe, in step order (MAIN first). */
    private Map<Long, List<String>> savedSteps(Long recipeId) {
        Map<Long, List<String>> steps = new LinkedHashMap<>();
        processRepository.findByRecipeId(recipeId).stream()
                .sorted(Comparator.comparing((Process process) -> process.getType() == null ? 1 : process.getType().ordinal())
                        .thenComparing(Process::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(process -> steps.put(process.getId(), stepSource.prepareStepContexts(process.getId()).steps().stream()
                        .map(step -> step.node().getId()).toList()));
        return steps;
    }

    private Map<Long, String> processNames(Long recipeId) {
        Map<Long, String> names = new HashMap<>();
        processRepository.findByRecipeId(recipeId).forEach(process -> names.put(process.getId(), process.getName()));
        return names;
    }

    /** Images still missing (per MAIN step / Preparation flow) and steps without usable narration: what approval would pay for. */
    private RecipeAiWorkflowEstimator.StepCounts remainingStepCounts(Long userId, Long recipeId, Map<Long, List<String>> steps) {
        int visual = 0;
        int narration = 0;
        for (Map.Entry<Long, List<String>> entry : steps.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            // Images are per MAIN step but per Preparation flow (one image for each flow's output step).
            Set<String> withImage = new HashSet<>();
            List<String> imageTargets = new ArrayList<>();
            processRepository.findById(entry.getKey()).ifPresent(process -> {
                process.getNodes().stream()
                        .filter(node -> hasImage(node.getData()))
                        .forEach(node -> withImage.add(node.getId()));
                imageTargets.addAll(RecipeProcessVisualizationService.visualTargetNodeIds(process));
            });
            visual += (int) imageTargets.stream().filter(nodeId -> !withImage.contains(nodeId)).count();
            try {
                narration += (int) narrationService.list(userId, recipeId, entry.getKey()).stream()
                        .filter(dto -> dto.isNarratable() && !"READY".equals(dto.getStatus()))
                        .count();
            } catch (NoSuchElementException e) {
                narration += entry.getValue().size();
            }
        }
        return RecipeAiWorkflowEstimator.StepCounts.exact(visual, narration);
    }

    /** A step has an image when the visualization service wrote one (flat) or the editor kept one (nested). */
    @SuppressWarnings("unchecked")
    static boolean hasImage(Map<String, Object> data) {
        if (data == null) {
            return false;
        }
        if (data.get("imageUrl") instanceof String url && StringUtils.hasText(url)) {
            return true;
        }
        return data.get("visualization") instanceof Map<?, ?> nested
                && ((Map<String, Object>) nested).get("imageUrl") instanceof String url && StringUtils.hasText(url);
    }

    private RecipeTemplate requireOwned(Long recipeId, Long userId) {
        RecipeTemplate recipe = recipeTemplateRepository.findById(recipeId)
                .orElseThrow(() -> new NoSuchElementException("Recipe not found: " + recipeId));
        if (userId == null || !userId.equals(recipe.getCreatedBy())) {
            throw new RecipeAccessDeniedException("You do not have permission to modify this recipe");
        }
        return recipe;
    }

    private RecipeAiWorkflow findOwned(Long userId, Long recipeId, String workflowId) {
        // A workflow of another user or recipe is reported exactly like a missing one.
        return workflowRepository.findById(workflowId)
                .filter(found -> Objects.equals(found.getUserId(), userId) && Objects.equals(found.getRecipeId(), recipeId))
                .orElseThrow(() -> new NoSuchElementException("AI Recipe Creation not found: " + workflowId));
    }

    static String activeKey(Long recipeId) {
        return "wf:" + recipeId;
    }

    private RecipeAiWorkflowResponseDTO toDto(RecipeAiWorkflow workflow, Resolution resolution, boolean reused,
                                              RecipeProcessGenerationJobResponseDTO generation) {
        RecipeProcessGenerationJob generationJob = generationJob(workflow);
        List<VisualizationJobResponseDTO> visualizationJobs = workflow.getVisualizationJobs().stream()
                .filter(ref -> ref.getJobId() != null)
                .map(ref -> {
                    try {
                        return visualizationJobService.getJobStatus(workflow.getUserId(), workflow.getRecipeId(), ref.getProcessId(), ref.getJobId());
                    } catch (NoSuchElementException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
        return RecipeAiWorkflowResponseDTO.builder()
                .workflowId(workflow.getId())
                .recipeId(workflow.getRecipeId())
                .status(resolution.status())
                .selectedTasks(workflow.getSelectedTasks())
                .tasks(resolution.tasks())
                .progressPercent(resolution.progressPercent())
                .cancellable(resolution.cancellable())
                .generationJobId(workflow.getGenerationJobId())
                .generationApplied(generationJob != null && generationJob.getResultAppliedAt() != null)
                .generation(generation)
                .visualizationJobs(visualizationJobs)
                .approvedRevision(workflow.getApprovedSnapshot() != null ? workflow.getApprovedSnapshot().getRevision() : null)
                .approvedAt(workflow.getApprovedSnapshot() != null ? workflow.getApprovedSnapshot().getApprovedAt() : null)
                .createdAt(workflow.getCreatedAt())
                .completedAt(workflow.getCompletedAt())
                .reused(reused)
                .build();
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
