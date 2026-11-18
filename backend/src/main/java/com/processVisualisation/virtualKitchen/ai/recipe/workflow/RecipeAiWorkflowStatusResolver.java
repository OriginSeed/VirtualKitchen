package com.processVisualisation.virtualKitchen.ai.recipe.workflow;

import com.processVisualisation.virtualKitchen.ai.recipe.generation.model.RecipeProcessGenerationJob;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.model.RecipeProcessGenerationJobStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.model.RecipeProcessGenerationStage;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.dto.StepNarrationResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.model.StepNarrationStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.RecipeStoryboard;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.StoryboardStage;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.model.VisualizationJob;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.model.VisualizationJobStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto.RecipeAiWorkflowResponseDTO.TaskDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiWorkflow;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiWorkflowStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Derives a workflow's task states, overall status and progress from the jobs doing the work. Pure:
 * every input is passed in, nothing is read or written here, so the same snapshot of the jobs
 * always gives the same answer.
 *
 * <h3>Progress</h3>
 * {@code overall = Σ(weightᵢ × fractionᵢ) / Σ weightᵢ} over the selected tasks that aren't SKIPPED,
 * where the fraction is
 * <ul>
 *   <li>PROCESS — the generation stage's percent, or 1 once generation has finished;</li>
 *   <li>VISUALS — steps processed (image made or failed) ÷ approved steps;</li>
 *   <li>NARRATION — steps processed (narration ready or failed) ÷ approved narratable steps;</li>
 * </ul>
 * and 0 for a downstream task that hasn't been approved yet. Weights come from
 * {@link RecipeAiWorkflowProperties.Weights}.
 */
@Component
public class RecipeAiWorkflowStatusResolver {

    private final RecipeAiWorkflowProperties properties;

    public RecipeAiWorkflowStatusResolver(RecipeAiWorkflowProperties properties) {
        this.properties = properties;
    }

    /**
     * Everything the status depends on.
     *
     * @param generationJob      the PROCESS task's generation job; null when PROCESS isn't selected
     * @param visualizationJobs  the jobs referenced by {@link RecipeAiWorkflow#getVisualizationJobs()}, by job id
     * @param narrations         narration state of the approved steps that still exist, by step id
     */
    public record Inputs(
            RecipeAiWorkflow workflow,
            RecipeProcessGenerationJob generationJob,
            Map<String, VisualizationJob> visualizationJobs,
            Map<String, StepNarrationResponseDTO> narrations,
            Instant now,
            RecipeStoryboard storyboard) {

        /** Inputs of a workflow whose visuals are per-process step visuals (no storyboard). */
        public Inputs(RecipeAiWorkflow workflow, RecipeProcessGenerationJob generationJob,
                      Map<String, VisualizationJob> visualizationJobs, Map<String, StepNarrationResponseDTO> narrations,
                      Instant now) {
            this(workflow, generationJob, visualizationJobs, narrations, now, null);
        }
    }

    public record Resolution(RecipeAiWorkflowStatus status, List<TaskDTO> tasks, int progressPercent, boolean cancellable) {

        public TaskDTO task(RecipeAiTaskType type) {
            return tasks.stream().filter(task -> task.getType() == type).findFirst().orElseThrow();
        }
    }

    public Resolution resolve(Inputs in) {
        RecipeAiWorkflow workflow = in.workflow();
        boolean approved = workflow.getApprovedSnapshot() != null;
        boolean cancelled = workflow.getStatus() == RecipeAiWorkflowStatus.CANCELLED;

        TaskDTO process = resolveProcess(workflow, in.generationJob(), approved);
        RecipeAiWorkflowStatus gate = gateStatus(workflow, process, approved, cancelled);

        TaskDTO visuals;
        TaskDTO narration;
        if (approved && !cancelled) {
            visuals = !workflow.isSelected(RecipeAiTaskType.VISUALS) ? skipped(RecipeAiTaskType.VISUALS)
                    : workflow.isStoryboardVisuals() ? resolveStoryboardVisuals(in.storyboard())
                    : resolveVisuals(workflow, in.visualizationJobs());
            narration = workflow.isSelected(RecipeAiTaskType.NARRATION)
                    ? resolveNarration(workflow, in.narrations(), in.now()) : skipped(RecipeAiTaskType.NARRATION);
        } else {
            visuals = notStarted(workflow, RecipeAiTaskType.VISUALS, gate);
            narration = notStarted(workflow, RecipeAiTaskType.NARRATION, gate);
        }
        List<TaskDTO> tasks = List.of(process, visuals, narration);

        RecipeAiWorkflowStatus status = approved && !cancelled ? downstreamStatus(workflow, tasks) : gate;
        boolean cancellable = !approved && status == RecipeAiWorkflowStatus.WAITING_FOR_APPROVAL;
        return new Resolution(status, tasks, overallProgress(tasks), cancellable);
    }

    // --- PROCESS -------------------------------------------------------------------------------

    private TaskDTO resolveProcess(RecipeAiWorkflow workflow, RecipeProcessGenerationJob job, boolean approved) {
        if (!workflow.isSelected(RecipeAiTaskType.PROCESS)) {
            return skipped(RecipeAiTaskType.PROCESS);
        }
        TaskDTO.TaskDTOBuilder task = TaskDTO.builder()
                .type(RecipeAiTaskType.PROCESS)
                .weight(weight(RecipeAiTaskType.PROCESS));
        if (job == null || job.getStatus() == null) {
            // Approval is only possible once generation completed, so an approved workflow's process is done.
            return approved
                    ? task.status(RecipeAiTaskStatus.COMPLETED).progressPercent(100).build()
                    : task.status(RecipeAiTaskStatus.PENDING).build();
        }
        RecipeProcessGenerationStage stage = job.getStage() != null ? job.getStage() : RecipeProcessGenerationStage.QUEUED;
        task.stage(stage.name());
        return switch (job.getStatus()) {
            case QUEUED -> task.status(RecipeAiTaskStatus.QUEUED).progressPercent(stage.getPercent()).build();
            case IN_PROGRESS -> task.status(RecipeAiTaskStatus.RUNNING).progressPercent(stage.getPercent()).build();
            case COMPLETED -> task.status(RecipeAiTaskStatus.COMPLETED).progressPercent(100).build();
            case FAILED -> task.status(RecipeAiTaskStatus.FAILED)
                    .progressPercent(100)
                    .errorMessage(job.getErrorMessage())
                    .retryable(!approved && workflow.getStatus() != RecipeAiWorkflowStatus.CANCELLED)
                    .build();
        };
    }

    /** The overall status before approval (or after a discard): decided by the process stage alone. */
    private static RecipeAiWorkflowStatus gateStatus(RecipeAiWorkflow workflow, TaskDTO process, boolean approved, boolean cancelled) {
        if (cancelled) {
            return RecipeAiWorkflowStatus.CANCELLED;
        }
        if (approved) {
            return RecipeAiWorkflowStatus.RUNNING_DOWNSTREAM_TASKS;
        }
        if (!workflow.isSelected(RecipeAiTaskType.PROCESS)) {
            return RecipeAiWorkflowStatus.WAITING_FOR_APPROVAL;
        }
        return switch (process.getStatus()) {
            case COMPLETED -> RecipeAiWorkflowStatus.WAITING_FOR_APPROVAL;
            case FAILED -> RecipeAiWorkflowStatus.FAILED;
            case PENDING -> workflow.getStatus() == RecipeAiWorkflowStatus.CREATED
                    ? RecipeAiWorkflowStatus.CREATED : RecipeAiWorkflowStatus.GENERATING_PROCESS;
            default -> RecipeAiWorkflowStatus.GENERATING_PROCESS;
        };
    }

    private TaskDTO notStarted(RecipeAiWorkflow workflow, RecipeAiTaskType type, RecipeAiWorkflowStatus gate) {
        if (!workflow.isSelected(type)) {
            return skipped(type);
        }
        RecipeAiTaskStatus status = switch (gate) {
            case WAITING_FOR_APPROVAL -> RecipeAiTaskStatus.WAITING_FOR_APPROVAL;
            // The process failed: its dependants never run (and never spend credits).
            case FAILED -> RecipeAiTaskStatus.SKIPPED;
            case CANCELLED -> RecipeAiTaskStatus.CANCELLED;
            default -> RecipeAiTaskStatus.PENDING;
        };
        return TaskDTO.builder().type(type).status(status).weight(weight(type)).build();
    }

    // --- VISUALS -------------------------------------------------------------------------------

    private TaskDTO resolveVisuals(RecipeAiWorkflow workflow, Map<String, VisualizationJob> jobsById) {
        Map<Long, Integer> approvedSteps = approvedStepCounts(workflow);
        int total = 0;
        int succeeded = 0;
        int failed = 0;
        boolean running = false;
        boolean anyStarted = false;
        String error = null;
        Set<Long> covered = new LinkedHashSet<>();
        for (RecipeAiWorkflow.VisualizationJobRef ref : workflow.getVisualizationJobs()) {
            covered.add(ref.getProcessId());
            VisualizationJob job = ref.getJobId() != null ? jobsById.get(ref.getJobId()) : null;
            if (job == null) {
                // The job couldn't be started (or no longer exists): all of its steps count as failed.
                int steps = approvedSteps.getOrDefault(ref.getProcessId(), 0);
                total += steps;
                failed += steps;
                if (error == null) {
                    error = "Visuals could not be started for one of the processes";
                }
                continue;
            }
            int jobTotal = job.getTotalSteps();
            int jobSucceeded = (int) job.getStepResults().stream().filter(VisualizationJob.StepResult::isSuccess).count();
            int jobFailed = job.getStepResults().size() - jobSucceeded;
            if (job.getStatus() == VisualizationJobStatus.FAILED) {
                jobFailed = Math.max(jobFailed, jobTotal - jobSucceeded);
            }
            if (job.getStatus() == VisualizationJobStatus.QUEUED || job.getStatus() == VisualizationJobStatus.IN_PROGRESS) {
                running = true;
                anyStarted |= job.getStatus() == VisualizationJobStatus.IN_PROGRESS;
            }
            total += jobTotal;
            succeeded += jobSucceeded;
            failed += jobFailed;
            if (error == null) {
                error = job.getErrorMessage() != null ? job.getErrorMessage()
                        : job.getStepResults().stream().map(VisualizationJob.StepResult::getErrorMessage)
                                .filter(Objects::nonNull).findFirst().orElse(null);
            }
        }
        // An approved process without a job reference yet is still being started.
        for (Map.Entry<Long, Integer> entry : approvedSteps.entrySet()) {
            if (!covered.contains(entry.getKey()) && entry.getValue() > 0) {
                total += entry.getValue();
                running = true;
            }
        }
        return countedTask(RecipeAiTaskType.VISUALS, total, succeeded, failed, running, anyStarted, failed > 0 ? error : null, null);
    }

    /** Stages of a storyboard, counted as the task's progress: plan, image prompt, image, cut and attach. */
    private static final int STORYBOARD_STAGES = StoryboardStage.values().length;

    /**
     * VISUALS as one storyboard: progress is the stage reached. It either completes (with its panels on the steps)
     * or fails as a whole, so a failure counts no step as made.
     */
    private TaskDTO resolveStoryboardVisuals(RecipeStoryboard storyboard) {
        if (storyboard == null || storyboard.getStatus() == null) {
            return countedTask(RecipeAiTaskType.VISUALS, STORYBOARD_STAGES, 0, STORYBOARD_STAGES, false, false,
                    "Visualize could not be started", null);
        }
        int reached = storyboard.getStage() == null ? 0 : storyboard.getStage().ordinal();
        TaskDTO task = switch (storyboard.getStatus()) {
            case QUEUED -> countedTask(RecipeAiTaskType.VISUALS, STORYBOARD_STAGES, 0, 0, true, false, null, null);
            case IN_PROGRESS -> countedTask(RecipeAiTaskType.VISUALS, STORYBOARD_STAGES, reached, 0, true, true, null, null);
            case COMPLETED -> countedTask(RecipeAiTaskType.VISUALS, STORYBOARD_STAGES, STORYBOARD_STAGES, 0, false, true, null, null);
            case FAILED -> countedTask(RecipeAiTaskType.VISUALS, STORYBOARD_STAGES, 0, STORYBOARD_STAGES, false, true,
                    storyboard.getErrorMessage() != null ? storyboard.getErrorMessage() : "Visualize failed", null);
        };
        task.setStage(storyboard.getStage() == null ? null : storyboard.getStage().name());
        return task;
    }

    // --- NARRATION -----------------------------------------------------------------------------

    private TaskDTO resolveNarration(RecipeAiWorkflow workflow, Map<String, StepNarrationResponseDTO> narrations, Instant now) {
        int total = 0;
        int succeeded = 0;
        int failed = 0;
        boolean running = false;
        Instant retryAfter = null;
        String error = null;
        for (RecipeAiWorkflow.ApprovedProcess process : workflow.getApprovedSnapshot().getProcesses()) {
            for (String stepId : process.getStepIds()) {
                StepNarrationResponseDTO narration = narrations.get(stepId);
                if (narration == null || !narration.isNarratable()) {
                    continue; // the step was deleted since approval, or has nothing to say
                }
                total++;
                StepNarrationStatus status = parseNarrationStatus(narration.getStatus());
                switch (status) {
                    // STALE: generated from the approved text; the step was edited afterwards.
                    case READY, STALE -> succeeded++;
                    case GENERATING -> running = true;
                    // NOT_GENERATED after approval means the generation died before finishing.
                    case FAILED, NOT_GENERATED -> {
                        failed++;
                        if (error == null) {
                            error = narration.getFailureReason();
                        }
                        if (narration.getRetryAfter() != null && narration.getRetryAfter().isAfter(now)
                                && (retryAfter == null || narration.getRetryAfter().isAfter(retryAfter))) {
                            retryAfter = narration.getRetryAfter();
                        }
                    }
                }
            }
        }
        return countedTask(RecipeAiTaskType.NARRATION, total, succeeded, failed, running, running,
                failed > 0 ? (error != null ? error : "Narration failed for some steps") : null, retryAfter);
    }

    private static StepNarrationStatus parseNarrationStatus(String status) {
        try {
            return status == null ? StepNarrationStatus.NOT_GENERATED : StepNarrationStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return StepNarrationStatus.NOT_GENERATED;
        }
    }

    // --- shared --------------------------------------------------------------------------------

    private TaskDTO countedTask(RecipeAiTaskType type, int total, int succeeded, int failed, boolean running,
                                boolean started, String error, Instant retryAfter) {
        RecipeAiTaskStatus status;
        if (running) {
            status = started || succeeded + failed > 0 ? RecipeAiTaskStatus.RUNNING : RecipeAiTaskStatus.QUEUED;
        } else {
            status = failed > 0 ? RecipeAiTaskStatus.FAILED : RecipeAiTaskStatus.COMPLETED;
        }
        int processed = Math.min(total, succeeded + failed);
        int percent = total == 0 ? (running ? 0 : 100) : (int) Math.floor(processed * 100.0 / total);
        return TaskDTO.builder()
                .type(type)
                .status(status)
                .weight(weight(type))
                .progressPercent(percent)
                .completed(succeeded)
                .failed(failed)
                .total(total)
                .errorMessage(error)
                .retryable(!running && failed > 0)
                .retryAfter(retryAfter)
                .build();
    }

    /**
     * After approval: still running while any downstream task runs; then COMPLETED if nothing failed,
     * PARTIALLY_COMPLETED if something failed but something else was created, FAILED otherwise.
     */
    private static RecipeAiWorkflowStatus downstreamStatus(RecipeAiWorkflow workflow, List<TaskDTO> tasks) {
        List<TaskDTO> selected = tasks.stream().filter(task -> workflow.isSelected(task.getType())).toList();
        boolean running = selected.stream().anyMatch(task ->
                task.getStatus() == RecipeAiTaskStatus.RUNNING || task.getStatus() == RecipeAiTaskStatus.QUEUED);
        if (running) {
            return RecipeAiWorkflowStatus.RUNNING_DOWNSTREAM_TASKS;
        }
        boolean anyFailed = selected.stream().anyMatch(task -> task.getStatus() == RecipeAiTaskStatus.FAILED);
        if (!anyFailed) {
            return RecipeAiWorkflowStatus.COMPLETED;
        }
        boolean anyCreated = selected.stream().anyMatch(task ->
                task.getStatus() == RecipeAiTaskStatus.COMPLETED || task.getCompleted() > 0);
        return anyCreated ? RecipeAiWorkflowStatus.PARTIALLY_COMPLETED : RecipeAiWorkflowStatus.FAILED;
    }

    private static int overallProgress(List<TaskDTO> tasks) {
        int weightSum = 0;
        double weighted = 0;
        for (TaskDTO task : tasks) {
            if (task.getStatus() == RecipeAiTaskStatus.SKIPPED || task.getStatus() == RecipeAiTaskStatus.CANCELLED) {
                continue;
            }
            weightSum += task.getWeight();
            weighted += task.getWeight() * (task.getProgressPercent() / 100.0);
        }
        return weightSum == 0 ? 0 : (int) Math.floor(weighted * 100 / weightSum);
    }

    private static Map<Long, Integer> approvedStepCounts(RecipeAiWorkflow workflow) {
        Map<Long, Integer> counts = new java.util.LinkedHashMap<>();
        for (RecipeAiWorkflow.ApprovedProcess process : workflow.getApprovedSnapshot().getProcesses()) {
            counts.put(process.getProcessId(), process.getStepIds() == null ? 0 : process.getStepIds().size());
        }
        return counts;
    }

    private TaskDTO skipped(RecipeAiTaskType type) {
        return TaskDTO.builder().type(type).status(RecipeAiTaskStatus.SKIPPED).weight(weight(type)).build();
    }

    private int weight(RecipeAiTaskType type) {
        RecipeAiWorkflowProperties.Weights weights = properties.getWeights();
        return Math.max(0, switch (type) {
            case PROCESS -> weights.getProcess();
            case VISUALS -> weights.getVisuals();
            case NARRATION -> weights.getNarration();
        });
    }

    /** Ids of every step the workflow approved, in step order (used to filter narration lookups). */
    public static List<String> approvedStepIds(RecipeAiWorkflow workflow) {
        List<String> ids = new ArrayList<>();
        if (workflow.getApprovedSnapshot() != null) {
            workflow.getApprovedSnapshot().getProcesses().forEach(process -> ids.addAll(process.getStepIds()));
        }
        return ids;
    }
}
