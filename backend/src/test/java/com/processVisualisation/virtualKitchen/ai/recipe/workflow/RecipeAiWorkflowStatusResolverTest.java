package com.processVisualisation.virtualKitchen.ai.recipe.workflow;

import com.processVisualisation.virtualKitchen.ai.recipe.generation.model.RecipeProcessGenerationJob;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.model.RecipeProcessGenerationJobStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.model.RecipeProcessGenerationStage;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.dto.StepNarrationResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.RecipeStoryboard;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.StoryboardStage;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.StoryboardStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.model.VisualizationJob;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto.RecipeAiWorkflowResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.model.VisualizationJobStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.RecipeAiWorkflowStatusResolver.Inputs;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.RecipeAiWorkflowStatusResolver.Resolution;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiWorkflow;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiWorkflowStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType.NARRATION;
import static com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType.PROCESS;
import static com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType.VISUALS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeAiWorkflowStatusResolverTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final List<String> STEPS = List.of("s1", "s2", "s3", "s4");

    private final RecipeAiWorkflowStatusResolver resolver = new RecipeAiWorkflowStatusResolver(new RecipeAiWorkflowProperties());

    // --- before approval -----------------------------------------------------------------------

    @Test
    void generatingProcessHoldsDownstreamTasksAsPending() {
        RecipeAiWorkflow workflow = workflow(PROCESS, VISUALS, NARRATION);
        Resolution resolution = resolve(workflow, generation(RecipeProcessGenerationJobStatus.IN_PROGRESS, RecipeProcessGenerationStage.CALLING_MODEL), Map.of(), Map.of());

        assertEquals(RecipeAiWorkflowStatus.GENERATING_PROCESS, resolution.status());
        assertEquals(RecipeAiTaskStatus.RUNNING, resolution.task(PROCESS).getStatus());
        assertEquals("CALLING_MODEL", resolution.task(PROCESS).getStage());
        assertEquals(RecipeAiTaskStatus.PENDING, resolution.task(VISUALS).getStatus());
        assertEquals(RecipeAiTaskStatus.PENDING, resolution.task(NARRATION).getStatus());
        // weights 2/5/3: process at 40% → 2×0.4 / 10 = 8%
        assertEquals(8, resolution.progressPercent());
        assertFalse(resolution.cancellable(), "generation is running: discarding would not stop it");
    }

    @Test
    void finishedGenerationWaitsForApproval() {
        RecipeAiWorkflow workflow = workflow(PROCESS, VISUALS, NARRATION);
        Resolution resolution = resolve(workflow, generation(RecipeProcessGenerationJobStatus.COMPLETED, RecipeProcessGenerationStage.COMPLETED), Map.of(), Map.of());

        assertEquals(RecipeAiWorkflowStatus.WAITING_FOR_APPROVAL, resolution.status());
        assertEquals(RecipeAiTaskStatus.COMPLETED, resolution.task(PROCESS).getStatus());
        assertEquals(RecipeAiTaskStatus.WAITING_FOR_APPROVAL, resolution.task(VISUALS).getStatus());
        assertEquals(RecipeAiTaskStatus.WAITING_FOR_APPROVAL, resolution.task(NARRATION).getStatus());
        assertEquals(20, resolution.progressPercent());
        assertTrue(resolution.cancellable());
    }

    @Test
    void failedGenerationFailsTheWorkflowAndSkipsItsDependants() {
        RecipeAiWorkflow workflow = workflow(PROCESS, VISUALS);
        RecipeProcessGenerationJob job = generation(RecipeProcessGenerationJobStatus.FAILED, RecipeProcessGenerationStage.VALIDATING_RESPONSE);
        job.setErrorMessage("invalid process");
        Resolution resolution = resolve(workflow, job, Map.of(), Map.of());

        assertEquals(RecipeAiWorkflowStatus.FAILED, resolution.status());
        assertEquals(RecipeAiTaskStatus.FAILED, resolution.task(PROCESS).getStatus());
        assertEquals("invalid process", resolution.task(PROCESS).getErrorMessage());
        assertTrue(resolution.task(PROCESS).isRetryable());
        assertEquals(RecipeAiTaskStatus.SKIPPED, resolution.task(VISUALS).getStatus());
        assertEquals(RecipeAiTaskStatus.SKIPPED, resolution.task(NARRATION).getStatus());
    }

    @Test
    void withoutProcessTheExistingProcessWaitsForApproval() {
        RecipeAiWorkflow workflow = workflow(VISUALS);
        Resolution resolution = resolve(workflow, null, Map.of(), Map.of());

        assertEquals(RecipeAiWorkflowStatus.WAITING_FOR_APPROVAL, resolution.status());
        assertEquals(RecipeAiTaskStatus.SKIPPED, resolution.task(PROCESS).getStatus());
        assertEquals(RecipeAiTaskStatus.WAITING_FOR_APPROVAL, resolution.task(VISUALS).getStatus());
    }

    @Test
    void discardedWorkflowIsCancelled() {
        RecipeAiWorkflow workflow = workflow(PROCESS, NARRATION);
        workflow.setStatus(RecipeAiWorkflowStatus.CANCELLED);
        Resolution resolution = resolve(workflow, generation(RecipeProcessGenerationJobStatus.COMPLETED, RecipeProcessGenerationStage.COMPLETED), Map.of(), Map.of());

        assertEquals(RecipeAiWorkflowStatus.CANCELLED, resolution.status());
        assertEquals(RecipeAiTaskStatus.CANCELLED, resolution.task(NARRATION).getStatus());
        assertFalse(resolution.cancellable());
    }

    // --- after approval ------------------------------------------------------------------------

    @Test
    void downstreamProgressIsWeightedByTask() {
        RecipeAiWorkflow workflow = approved(PROCESS, VISUALS, NARRATION);
        // visuals 2/4 processed, narration 3/4 processed
        VisualizationJob job = vizJob("j1", VisualizationJobStatus.IN_PROGRESS, 4, true, true);
        Resolution resolution = resolve(workflow, completedGeneration(), Map.of("j1", job),
                narrations("READY", "READY", "READY", "GENERATING"));

        assertEquals(RecipeAiWorkflowStatus.RUNNING_DOWNSTREAM_TASKS, resolution.status());
        assertEquals(RecipeAiTaskStatus.RUNNING, resolution.task(VISUALS).getStatus());
        assertEquals(2, resolution.task(VISUALS).getCompleted());
        assertEquals(4, resolution.task(VISUALS).getTotal());
        assertEquals(50, resolution.task(VISUALS).getProgressPercent());
        assertEquals(3, resolution.task(NARRATION).getCompleted());
        assertEquals(75, resolution.task(NARRATION).getProgressPercent());
        // (2×1 + 5×0.5 + 3×0.75) / 10 = 67.5% → 67
        assertEquals(67, resolution.progressPercent());
        assertFalse(resolution.cancellable());
    }

    @Test
    void visualsFailingWhileNarrationSucceedsIsPartialSuccess() {
        RecipeAiWorkflow workflow = approved(PROCESS, VISUALS, NARRATION);
        VisualizationJob job = vizJob("j1", VisualizationJobStatus.COMPLETED_WITH_ERRORS, 4, true, true, false, false);
        Resolution resolution = resolve(workflow, completedGeneration(), Map.of("j1", job),
                narrations("READY", "READY", "READY", "READY"));

        assertEquals(RecipeAiWorkflowStatus.PARTIALLY_COMPLETED, resolution.status());
        assertEquals(RecipeAiTaskStatus.FAILED, resolution.task(VISUALS).getStatus());
        assertEquals(2, resolution.task(VISUALS).getCompleted());
        assertEquals(2, resolution.task(VISUALS).getFailed());
        assertTrue(resolution.task(VISUALS).isRetryable());
        assertEquals(RecipeAiTaskStatus.COMPLETED, resolution.task(NARRATION).getStatus());
        assertFalse(resolution.task(NARRATION).isRetryable());
        assertEquals(100, resolution.progressPercent());
    }

    @Test
    void narrationFailingWhileVisualsSucceedIsPartialSuccess() {
        RecipeAiWorkflow workflow = approved(PROCESS, VISUALS, NARRATION);
        VisualizationJob job = vizJob("j1", VisualizationJobStatus.COMPLETED, 4, true, true, true, true);
        Map<String, StepNarrationResponseDTO> narrations = narrations("READY", "FAILED", "READY", "STALE");
        narrations.get("s2").setRetryAfter(NOW.plusSeconds(20));
        Resolution resolution = resolve(workflow, completedGeneration(), Map.of("j1", job), narrations);

        assertEquals(RecipeAiWorkflowStatus.PARTIALLY_COMPLETED, resolution.status());
        assertEquals(RecipeAiTaskStatus.COMPLETED, resolution.task(VISUALS).getStatus());
        assertEquals(RecipeAiTaskStatus.FAILED, resolution.task(NARRATION).getStatus());
        assertEquals(3, resolution.task(NARRATION).getCompleted(), "STALE narration was generated from the approved text");
        assertEquals(1, resolution.task(NARRATION).getFailed());
        assertEquals(NOW.plusSeconds(20), resolution.task(NARRATION).getRetryAfter());
    }

    @Test
    void everythingSucceedingCompletesTheWorkflow() {
        RecipeAiWorkflow workflow = approved(PROCESS, VISUALS, NARRATION);
        Resolution resolution = resolve(workflow, completedGeneration(),
                Map.of("j1", vizJob("j1", VisualizationJobStatus.COMPLETED, 4, true, true, true, true)),
                narrations("READY", "READY", "READY", "READY"));

        assertEquals(RecipeAiWorkflowStatus.COMPLETED, resolution.status());
        assertEquals(100, resolution.progressPercent());
    }

    @Test
    void processOnlyCompletesOnApproval() {
        RecipeAiWorkflow workflow = approved(PROCESS);
        Resolution resolution = resolve(workflow, completedGeneration(), Map.of(), Map.of());

        assertEquals(RecipeAiWorkflowStatus.COMPLETED, resolution.status());
        assertEquals(RecipeAiTaskStatus.SKIPPED, resolution.task(VISUALS).getStatus());
        assertEquals(RecipeAiTaskStatus.SKIPPED, resolution.task(NARRATION).getStatus());
    }

    @Test
    void everyDownstreamTaskFailingWithoutAProcessFailsTheWorkflow() {
        RecipeAiWorkflow workflow = approved(VISUALS);
        Resolution resolution = resolve(workflow, null,
                Map.of("j1", vizJob("j1", VisualizationJobStatus.FAILED, 4)), Map.of());

        assertEquals(RecipeAiWorkflowStatus.FAILED, resolution.status());
        assertEquals(4, resolution.task(VISUALS).getFailed());
    }

    @Test
    void deadOrNeverStartedNarrationCountsAsFailedAndUnnarratableStepsAreIgnored() {
        RecipeAiWorkflow workflow = approved(NARRATION);
        Map<String, StepNarrationResponseDTO> narrations = narrations("READY", "NOT_GENERATED", "READY", "NOT_GENERATED");
        narrations.get("s4").setNarratable(false);
        narrations.remove("s3"); // deleted after approval
        Resolution resolution = resolve(workflow, null, Map.of(), narrations);

        assertEquals(RecipeAiTaskStatus.FAILED, resolution.task(NARRATION).getStatus());
        assertEquals(2, resolution.task(NARRATION).getTotal());
        assertEquals(1, resolution.task(NARRATION).getFailed());
    }

    @Test
    void visualsWhoseJobCouldNotStartCountAsFailed() {
        RecipeAiWorkflow workflow = approved(VISUALS);
        workflow.setVisualizationJobs(List.of(new RecipeAiWorkflow.VisualizationJobRef(10L, null)));
        Resolution resolution = resolve(workflow, null, Map.of(), Map.of());

        assertEquals(RecipeAiTaskStatus.FAILED, resolution.task(VISUALS).getStatus());
        assertEquals(4, resolution.task(VISUALS).getFailed());
        assertNotNull(resolution.task(VISUALS).getErrorMessage());
        assertTrue(resolution.task(VISUALS).isRetryable());
    }

    @Test
    void customWeightsChangeTheOverallFigure() {
        RecipeAiWorkflowProperties properties = new RecipeAiWorkflowProperties();
        properties.getWeights().setVisuals(1);
        properties.getWeights().setNarration(1);
        properties.getWeights().setProcess(0);
        RecipeAiWorkflowStatusResolver custom = new RecipeAiWorkflowStatusResolver(properties);
        RecipeAiWorkflow workflow = approved(PROCESS, VISUALS, NARRATION);
        Resolution resolution = custom.resolve(new Inputs(workflow, completedGeneration(),
                Map.of("j1", vizJob("j1", VisualizationJobStatus.COMPLETED, 4, true, true, true, true)),
                narrations("GENERATING", "GENERATING", "GENERATING", "GENERATING"), NOW));

        assertEquals(50, resolution.progressPercent());
    }

    @Test
    void visualsAsAStoryboard_followItsStage_thenCompleteOrFailAsAWhole() {
        RecipeAiWorkflow workflow = approved(VISUALS);
        workflow.setStoryboardVisuals(true);
        workflow.setStoryboardJobId("sb1");

        RecipeAiWorkflowResponseDTO.TaskDTO running = storyboardTask(workflow, StoryboardStatus.IN_PROGRESS, StoryboardStage.IMAGE);
        assertEquals(RecipeAiTaskStatus.RUNNING, running.getStatus());
        assertEquals(50, running.getProgressPercent());
        assertEquals("IMAGE", running.getStage());

        RecipeAiWorkflowResponseDTO.TaskDTO done = storyboardTask(workflow, StoryboardStatus.COMPLETED, StoryboardStage.CROP);
        assertEquals(RecipeAiTaskStatus.COMPLETED, done.getStatus());
        assertEquals(100, done.getProgressPercent());

        RecipeAiWorkflowResponseDTO.TaskDTO failed = storyboardTask(workflow, StoryboardStatus.FAILED, StoryboardStage.IMAGE);
        assertEquals(RecipeAiTaskStatus.FAILED, failed.getStatus());
        assertEquals(0, failed.getCompleted(), "a failed storyboard made no step image");
        assertTrue(failed.isRetryable());
        assertEquals("provider down", failed.getErrorMessage());

        RecipeAiWorkflowResponseDTO.TaskDTO notStarted = resolver.resolve(new Inputs(workflow, null, Map.of(), Map.of(), NOW, null)).task(VISUALS);
        assertEquals(RecipeAiTaskStatus.FAILED, notStarted.getStatus());
        assertTrue(notStarted.isRetryable());
    }

    private RecipeAiWorkflowResponseDTO.TaskDTO storyboardTask(RecipeAiWorkflow workflow, StoryboardStatus status, StoryboardStage stage) {
        RecipeStoryboard storyboard = new RecipeStoryboard();
        storyboard.setId("sb1");
        storyboard.setStatus(status);
        storyboard.setStage(stage);
        if (status == StoryboardStatus.FAILED) storyboard.setErrorMessage("provider down");
        return resolver.resolve(new Inputs(workflow, null, Map.of(), Map.of(), NOW, storyboard)).task(VISUALS);
    }

    // --- helpers -------------------------------------------------------------------------------

    private Resolution resolve(RecipeAiWorkflow workflow, RecipeProcessGenerationJob generation,
                               Map<String, VisualizationJob> jobs, Map<String, StepNarrationResponseDTO> narrations) {
        return resolver.resolve(new Inputs(workflow, generation, jobs, narrations, NOW));
    }

    private static RecipeAiWorkflow workflow(RecipeAiTaskType... tasks) {
        RecipeAiWorkflow workflow = new RecipeAiWorkflow();
        workflow.setId("wf");
        workflow.setRecipeId(100L);
        workflow.setUserId(7L);
        workflow.setSelectedTasks(List.of(tasks));
        workflow.setStatus(List.of(tasks).contains(PROCESS)
                ? RecipeAiWorkflowStatus.GENERATING_PROCESS : RecipeAiWorkflowStatus.WAITING_FOR_APPROVAL);
        return workflow;
    }

    private static RecipeAiWorkflow approved(RecipeAiTaskType... tasks) {
        RecipeAiWorkflow workflow = workflow(tasks);
        workflow.setStatus(RecipeAiWorkflowStatus.RUNNING_DOWNSTREAM_TASKS);
        workflow.setApprovedSnapshot(new RecipeAiWorkflow.ApprovedSnapshot(3L, NOW,
                List.of(new RecipeAiWorkflow.ApprovedProcess(10L, "Main", STEPS))));
        if (List.of(tasks).contains(VISUALS)) {
            workflow.setVisualizationJobs(List.of(new RecipeAiWorkflow.VisualizationJobRef(10L, "j1")));
        }
        return workflow;
    }

    private static RecipeProcessGenerationJob generation(RecipeProcessGenerationJobStatus status, RecipeProcessGenerationStage stage) {
        RecipeProcessGenerationJob job = new RecipeProcessGenerationJob();
        job.setId("gen");
        job.setStatus(status);
        job.setStage(stage);
        return job;
    }

    private static RecipeProcessGenerationJob completedGeneration() {
        return generation(RecipeProcessGenerationJobStatus.COMPLETED, RecipeProcessGenerationStage.COMPLETED);
    }

    private static VisualizationJob vizJob(String id, VisualizationJobStatus status, int total, boolean... stepSuccess) {
        VisualizationJob job = new VisualizationJob();
        job.setId(id);
        job.setStatus(status);
        job.setTotalSteps(total);
        for (int i = 0; i < stepSuccess.length; i++) {
            VisualizationJob.StepResult result = new VisualizationJob.StepResult();
            result.setStepId(STEPS.get(i));
            result.setSuccess(stepSuccess[i]);
            if (!stepSuccess[i]) {
                result.setErrorMessage("image failed");
            }
            job.getStepResults().add(result);
        }
        job.setCompletedSteps(stepSuccess.length);
        return job;
    }

    private static Map<String, StepNarrationResponseDTO> narrations(String... statuses) {
        Map<String, StepNarrationResponseDTO> byStep = new HashMap<>();
        for (int i = 0; i < statuses.length; i++) {
            byStep.put(STEPS.get(i), StepNarrationResponseDTO.builder()
                    .stepId(STEPS.get(i)).status(statuses[i]).narratable(true).build());
        }
        return byStep;
    }
}
