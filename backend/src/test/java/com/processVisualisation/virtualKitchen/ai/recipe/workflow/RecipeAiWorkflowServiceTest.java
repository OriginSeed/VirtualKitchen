package com.processVisualisation.virtualKitchen.ai.recipe.workflow;

import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessGenerationJobRepository;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessGenerationJobService;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.RecipeProcessGenerationJobResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.RecipeProcessGenerationRequestDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.model.RecipeProcessGenerationJob;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.model.RecipeProcessGenerationJobStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.model.RecipeProcessGenerationStage;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.StepNarrationService;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.dto.StepNarrationResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationJobService;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationJobRepository;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.dto.VisualizationJobResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.model.VisualizationJob;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.model.VisualizationJobStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto.RecipeAiTaskSelectionDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto.RecipeAiWorkflowCreateRequestDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto.RecipeAiWorkflowResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiWorkflow;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiWorkflowStatus;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeRevisionConflictException;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessNodeKind;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessType;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import com.processVisualisation.virtualKitchen.recipe.repository.ProcessRepository;
import com.processVisualisation.virtualKitchen.recipe.repository.RecipeTemplateRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType.NARRATION;
import static com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType.PROCESS;
import static com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType.VISUALS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RecipeAiWorkflowServiceTest {

    private static final Long USER = 7L;
    private static final Long RECIPE = 100L;
    private static final Long MAIN = 10L;
    private static final Long SUB = 11L;
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private RecipeAiWorkflowRepository workflowRepository;
    private MongoTemplate mongoTemplate;
    private RecipeTemplateRepository recipeRepository;
    private ProcessRepository processRepository;
    private RecipeProcessVisualizationService stepSource;
    private RecipeProcessGenerationJobService generationJobService;
    private RecipeProcessGenerationJobRepository generationJobRepository;
    private RecipeProcessVisualizationJobService visualizationJobService;
    private VisualizationJobRepository visualizationJobRepository;
    private StepNarrationService narrationService;
    private RecipeAiWorkflowService service;

    private RecipeTemplate recipe;
    /** The single stored workflow; mongoTemplate updates are applied to it so the service sees its own writes. */
    private RecipeAiWorkflow stored;
    private final Map<String, RecipeProcessGenerationJob> generationJobs = new HashMap<>();
    private final Map<Long, List<String>> savedStepIds = new HashMap<>();

    @BeforeEach
    void setUp() {
        workflowRepository = mock(RecipeAiWorkflowRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        recipeRepository = mock(RecipeTemplateRepository.class);
        processRepository = mock(ProcessRepository.class);
        stepSource = mock(RecipeProcessVisualizationService.class);
        generationJobService = mock(RecipeProcessGenerationJobService.class);
        generationJobRepository = mock(RecipeProcessGenerationJobRepository.class);
        visualizationJobService = mock(RecipeProcessVisualizationJobService.class);
        visualizationJobRepository = mock(VisualizationJobRepository.class);
        narrationService = mock(StepNarrationService.class);
        RecipeAiWorkflowEstimator estimator = mock(RecipeAiWorkflowEstimator.class);
        service = new RecipeAiWorkflowService(workflowRepository, mongoTemplate, recipeRepository, processRepository,
                stepSource, generationJobService, generationJobRepository, visualizationJobService,
                visualizationJobRepository, narrationService, estimator,
                new RecipeAiWorkflowStatusResolver(new RecipeAiWorkflowProperties()),
                Clock.fixed(NOW, ZoneOffset.UTC));

        recipe = new RecipeTemplate();
        recipe.setId(RECIPE);
        recipe.setCreatedBy(USER);
        recipe.setProcessRevision(3L);
        when(recipeRepository.findById(RECIPE)).thenReturn(Optional.of(recipe));

        savedStepIds.put(MAIN, List.of("s1", "s2"));
        savedStepIds.put(SUB, List.of("s3"));
        when(processRepository.findByRecipeId(RECIPE)).thenAnswer(inv -> List.of(readyMain(), readyPreparation()));
        when(stepSource.prepareStepContexts(anyLong())).thenAnswer(inv -> preparation(inv.getArgument(0)));

        when(workflowRepository.insert(any(RecipeAiWorkflow.class))).thenAnswer(inv -> {
            stored = inv.getArgument(0);
            return stored;
        });
        when(workflowRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(stored).filter(w -> w.getId().equals(inv.getArgument(0))));
        when(workflowRepository.findByActiveKey(any())).thenAnswer(inv ->
                Optional.ofNullable(stored).filter(w -> inv.getArgument(0).equals(w.getActiveKey())));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(RecipeAiWorkflow.class))).thenAnswer(inv -> {
            apply(inv);
            return null;
        });
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RecipeAiWorkflow.class)))
                .thenAnswer(inv -> {
                    Document criteria = inv.getArgument(0, Query.class).getQueryObject();
                    if (criteria.containsKey("approvedSnapshot") && stored.getApprovedSnapshot() != null) {
                        return null;
                    }
                    apply(inv);
                    return stored;
                });

        when(generationJobRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(generationJobs.get(inv.<String>getArgument(0))));
        when(generationJobService.startJob(eq(USER), eq(RECIPE), any(RecipeProcessGenerationRequestDTO.class))).thenAnswer(inv -> {
            RecipeProcessGenerationJob job = new RecipeProcessGenerationJob();
            job.setId("gen-" + (generationJobs.size() + 1));
            job.setStatus(RecipeProcessGenerationJobStatus.QUEUED);
            job.setStage(RecipeProcessGenerationStage.QUEUED);
            generationJobs.put(job.getId(), job);
            return RecipeProcessGenerationJobResponseDTO.builder().jobId(job.getId()).status("QUEUED").mode("CREATE").build();
        });
        when(visualizationJobService.startJob(eq(USER), eq(RECIPE), anyLong(), anyList())).thenAnswer(inv ->
                VisualizationJobResponseDTO.builder().jobId("viz-" + inv.getArgument(2)).processId(inv.getArgument(2)).build());
        // Jobs this test started are still queued unless a test says otherwise.
        when(visualizationJobRepository.findAllById(any())).thenAnswer(inv -> {
            List<VisualizationJob> jobs = new ArrayList<>();
            inv.<Iterable<String>>getArgument(0).forEach(id -> jobs.add(vizJob(id, VisualizationJobStatus.QUEUED, 1, 0)));
            jobs.forEach(job -> {
                job.getStepResults().clear();
                job.setCompletedSteps(0);
            });
            return jobs;
        });
    }

    // --- create --------------------------------------------------------------------------------

    @Test
    void creatingWithProcessStartsOnlyTheGeneration() {
        RecipeAiWorkflowResponseDTO created = service.create(USER, RECIPE, request("Boil 2 eggs", true));

        ArgumentCaptor<RecipeProcessGenerationRequestDTO> generation = ArgumentCaptor.forClass(RecipeProcessGenerationRequestDTO.class);
        verify(generationJobService).startJob(eq(USER), eq(RECIPE), generation.capture());
        assertEquals("Boil 2 eggs", generation.getValue().getRecipeText());
        assertEquals(RecipeAiWorkflowStatus.GENERATING_PROCESS, created.getStatus());
        assertEquals(List.of(PROCESS, VISUALS, NARRATION), created.getSelectedTasks());
        assertEquals("gen-1", created.getGeneration().getJobId());
        assertEquals(RecipeAiTaskStatus.PENDING, task(created, VISUALS).getStatus());
        // No downstream work, and no image/TTS credits, before approval.
        verifyNoInteractions(visualizationJobService, narrationService);
    }

    @Test
    void creatingWithoutProcessWaitsForApprovalOfTheExistingProcess() {
        RecipeAiWorkflowResponseDTO created = service.create(USER, RECIPE,
                new RecipeAiWorkflowCreateRequestDTO(new RecipeAiTaskSelectionDTO(List.of(VISUALS), false), null));

        assertEquals(RecipeAiWorkflowStatus.WAITING_FOR_APPROVAL, created.getStatus());
        assertEquals(RecipeAiTaskStatus.SKIPPED, task(created, PROCESS).getStatus());
        assertEquals(RecipeAiTaskStatus.WAITING_FOR_APPROVAL, task(created, VISUALS).getStatus());
        assertTrue(created.isCancellable());
        verifyNoInteractions(generationJobService, visualizationJobService, narrationService);
    }

    @Test
    void visualsWithoutProcessNeedExistingSteps() {
        savedStepIds.put(MAIN, List.of());
        savedStepIds.put(SUB, List.of());

        assertThrows(RecipeAiWorkflowException.class, () -> service.create(USER, RECIPE,
                new RecipeAiWorkflowCreateRequestDTO(new RecipeAiTaskSelectionDTO(List.of(NARRATION), false), null)));
        verify(workflowRepository, never()).insert(any(RecipeAiWorkflow.class));
    }

    @Test
    void anOpenWorkflowIsReusedInsteadOfDuplicated() {
        service.create(USER, RECIPE, request("Boil 2 eggs", true));
        RecipeAiWorkflowResponseDTO second = service.create(USER, RECIPE, request("Fry an egg", true));

        assertTrue(second.isReused());
        verify(generationJobService).startJob(eq(USER), eq(RECIPE), any(RecipeProcessGenerationRequestDTO.class));
    }

    @Test
    void onlyTheOwnerCanStartIt() {
        assertThrows(RecipeAccessDeniedException.class, () -> service.create(99L, RECIPE, request("Boil 2 eggs", true)));
    }

    @Test
    void aFailedGenerationStartClosesTheWorkflow() {
        when(generationJobService.startJob(eq(USER), eq(RECIPE), any(RecipeProcessGenerationRequestDTO.class)))
                .thenThrow(new IllegalStateException("pool down"));

        assertThrows(IllegalStateException.class, () -> service.create(USER, RECIPE, request("Boil 2 eggs", true)));
        assertEquals(RecipeAiWorkflowStatus.FAILED, stored.getStatus());
        assertNull(stored.getActiveKey());
    }

    // --- process generation outcome ------------------------------------------------------------

    @Test
    void failedGenerationFailsTheWorkflowAndCanBeRetried() {
        service.create(USER, RECIPE, request("Boil 2 eggs", true));
        generationJobs.get("gen-1").setStatus(RecipeProcessGenerationJobStatus.FAILED);
        generationJobs.get("gen-1").setErrorMessage("The AI returned an invalid process");

        RecipeAiWorkflowResponseDTO status = service.getStatus(USER, RECIPE, stored.getId());
        assertEquals(RecipeAiWorkflowStatus.FAILED, status.getStatus());
        assertEquals(RecipeAiTaskStatus.SKIPPED, task(status, VISUALS).getStatus());
        assertNull(stored.getActiveKey(), "a finished workflow no longer blocks the recipe");

        RecipeAiWorkflowResponseDTO retried = service.retryTask(USER, RECIPE, stored.getId(), PROCESS);
        assertEquals(RecipeAiWorkflowStatus.GENERATING_PROCESS, retried.getStatus());
        assertEquals("gen-2", stored.getGenerationJobId());
        verifyNoInteractions(visualizationJobService, narrationService);
    }

    @Test
    void completedGenerationWaitsForApproval() {
        service.create(USER, RECIPE, request("Boil 2 eggs", true));
        completeGeneration(false);

        RecipeAiWorkflowResponseDTO status = service.getStatus(USER, RECIPE, stored.getId());
        assertEquals(RecipeAiWorkflowStatus.WAITING_FOR_APPROVAL, status.getStatus());
        assertFalse(status.isGenerationApplied());
        verifyNoInteractions(visualizationJobService, narrationService);
    }

    // --- approval ------------------------------------------------------------------------------

    @Test
    void approvalIsRefusedUntilTheGeneratedProcessReachedTheEditor() {
        service.create(USER, RECIPE, request("Boil 2 eggs", true));
        completeGeneration(false);

        assertThrows(RecipeAiWorkflowException.class, () -> service.approve(USER, RECIPE, stored.getId(), 3L));
        verifyNoInteractions(visualizationJobService, narrationService);
    }

    @Test
    void approvalIsRefusedWhenTheSavedRecipeMovedOn() {
        service.create(USER, RECIPE, request("Boil 2 eggs", true));
        completeGeneration(true);
        recipe.setProcessRevision(4L); // saved again after the editor's approval snapshot

        assertThrows(RecipeRevisionConflictException.class, () -> service.approve(USER, RECIPE, stored.getId(), 3L));
        assertNull(stored.getApprovedSnapshot());
        verifyNoInteractions(visualizationJobService, narrationService);
    }

    @Test
    void approvalStartsTheSelectedTasksFromTheEditedSavedProcess() {
        service.create(USER, RECIPE, request("Boil 2 eggs", true));
        completeGeneration(true);
        // The user edited the generated process before approving: a step was replaced and one added.
        savedStepIds.put(MAIN, List.of("s1", "s2-edited", "s4-new"));

        RecipeAiWorkflowResponseDTO approved = service.approve(USER, RECIPE, stored.getId(), 3L);

        assertEquals(RecipeAiWorkflowStatus.RUNNING_DOWNSTREAM_TASKS, approved.getStatus());
        assertEquals(3L, approved.getApprovedRevision());
        assertEquals(List.of("s1", "s2-edited", "s4-new"), stored.getApprovedSnapshot().getProcesses().get(0).getStepIds());
        // Both processes share one prompt scope, so their image prompts are written in a single text call.
        verify(visualizationJobService).startJob(eq(USER), eq(RECIPE), eq(MAIN), argThat(scope -> scope.containsAll(List.of(MAIN, SUB))));
        verify(visualizationJobService).startJob(eq(USER), eq(RECIPE), eq(SUB), argThat(scope -> scope.containsAll(List.of(MAIN, SUB))));
        verify(narrationService).ensureAll(USER, RECIPE, MAIN, List.of("s1", "s2-edited", "s4-new"));
        verify(narrationService).ensureAll(USER, RECIPE, SUB, List.of("s3"));
        assertEquals(List.of("viz-10", "viz-11"), stored.getVisualizationJobs().stream().map(RecipeAiWorkflow.VisualizationJobRef::getJobId).toList());
    }

    @Test
    void approvalDoesNotStartUnselectedTasks() {
        service.create(USER, RECIPE, new RecipeAiWorkflowCreateRequestDTO(new RecipeAiTaskSelectionDTO(List.of(PROCESS, NARRATION), false), "Boil 2 eggs"));
        completeGeneration(true);

        service.approve(USER, RECIPE, stored.getId(), 3L);

        verify(visualizationJobService, never()).startJob(any(), any(), any(), any());
        verify(narrationService).ensureAll(USER, RECIPE, MAIN, List.of("s1", "s2"));
    }

    @Test
    void approvalForVisualsOrNarrationIsRefusedWhileTheRecipeIsNotReady() {
        // The main process has nothing in it: there is no recipe to visualize or narrate yet.
        when(processRepository.findByRecipeId(RECIPE)).thenAnswer(inv -> List.of(process(MAIN, ProcessType.MAIN), readyPreparation()));
        service.create(USER, RECIPE, new RecipeAiWorkflowCreateRequestDTO(new RecipeAiTaskSelectionDTO(List.of(PROCESS, NARRATION), false), "Boil 2 eggs"));
        completeGeneration(true);

        RecipeAiWorkflowException error = assertThrows(RecipeAiWorkflowException.class, () -> service.approve(USER, RECIPE, stored.getId(), 3L));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatus());
        verifyNoInteractions(narrationService);
    }

    @Test
    void processOnlyApprovalCompletesWithoutDownstreamWork() {
        service.create(USER, RECIPE, new RecipeAiWorkflowCreateRequestDTO(new RecipeAiTaskSelectionDTO(List.of(PROCESS), false), "Boil 2 eggs"));
        completeGeneration(true);

        service.approve(USER, RECIPE, stored.getId(), 3L);
        RecipeAiWorkflowResponseDTO status = service.getStatus(USER, RECIPE, stored.getId());

        assertEquals(RecipeAiWorkflowStatus.COMPLETED, status.getStatus());
        verifyNoInteractions(visualizationJobService, narrationService);
    }

    @Test
    void visualsFailingToStartDoesNotBlockNarration() {
        when(visualizationJobService.startJob(eq(USER), eq(RECIPE), anyLong(), anyList())).thenThrow(new IllegalStateException("queue full"));
        service.create(USER, RECIPE, request("Boil 2 eggs", true));
        completeGeneration(true);

        RecipeAiWorkflowResponseDTO approved = service.approve(USER, RECIPE, stored.getId(), 3L);

        verify(narrationService).ensureAll(USER, RECIPE, MAIN, List.of("s1", "s2"));
        verify(narrationService).ensureAll(USER, RECIPE, SUB, List.of("s3"));
        assertEquals(RecipeAiTaskStatus.FAILED, task(approved, VISUALS).getStatus());
        assertTrue(task(approved, VISUALS).isRetryable());
    }

    @Test
    void approvingTwiceStartsTheWorkOnce() {
        service.create(USER, RECIPE, request("Boil 2 eggs", true));
        completeGeneration(true);
        service.approve(USER, RECIPE, stored.getId(), 3L);

        assertThrows(RecipeAiWorkflowException.class, () -> service.approve(USER, RECIPE, stored.getId(), 3L));
        verify(visualizationJobService).startJob(eq(USER), eq(RECIPE), eq(MAIN), anyList());
    }

    // --- retry ---------------------------------------------------------------------------------

    @Test
    void retryingVisualsRestartsOnlyProcessesWithFailures() {
        approvedWorkflow(VISUALS);
        stored.setVisualizationJobs(new ArrayList<>(List.of(
                new RecipeAiWorkflow.VisualizationJobRef(MAIN, "j-main"),
                new RecipeAiWorkflow.VisualizationJobRef(SUB, "j-sub"))));
        doReturn(List.of(
                vizJob("j-main", VisualizationJobStatus.COMPLETED, 2, 2),
                vizJob("j-sub", VisualizationJobStatus.COMPLETED_WITH_ERRORS, 1, 0)))
                .when(visualizationJobRepository).findAllById(any());

        service.retryTask(USER, RECIPE, stored.getId(), VISUALS);

        verify(visualizationJobService).startJob(eq(USER), eq(RECIPE), eq(SUB), anyList());
        verify(visualizationJobService, never()).startJob(eq(USER), eq(RECIPE), eq(MAIN), anyList());
        assertEquals("viz-11", stored.getVisualizationJobs().get(1).getJobId());
        assertEquals("j-main", stored.getVisualizationJobs().get(0).getJobId(), "finished images are kept");
    }

    @Test
    void retryingNarrationReRequestsOnlyFailedSteps() {
        approvedWorkflow(NARRATION);
        when(narrationService.list(USER, RECIPE, MAIN)).thenReturn(List.of(narration("s1", "READY"), narration("s2", "FAILED")));
        when(narrationService.list(USER, RECIPE, SUB)).thenReturn(List.of(narration("s3", "READY")));

        service.retryTask(USER, RECIPE, stored.getId(), NARRATION);

        verify(narrationService).ensureAll(USER, RECIPE, MAIN, List.of("s2"));
        verify(narrationService, never()).ensureAll(eq(USER), eq(RECIPE), eq(SUB), any());
    }

    @Test
    void aTaskThatHasNotFailedCannotBeRetried() {
        approvedWorkflow(NARRATION);
        when(narrationService.list(USER, RECIPE, MAIN)).thenReturn(List.of(narration("s1", "READY"), narration("s2", "READY")));

        assertThrows(RecipeAiWorkflowException.class, () -> service.retryTask(USER, RECIPE, stored.getId(), NARRATION));
        assertThrows(RecipeAiWorkflowException.class, () -> service.retryTask(USER, RECIPE, stored.getId(), VISUALS));
    }

    // --- discard -------------------------------------------------------------------------------

    @Test
    void discardIsOnlyPossibleWhileNothingRuns() {
        service.create(USER, RECIPE, request("Boil 2 eggs", true));
        assertThrows(RecipeAiWorkflowException.class, () -> service.discard(USER, RECIPE, stored.getId()),
                "generation is running and can't be stopped");

        completeGeneration(false);
        RecipeAiWorkflowResponseDTO discarded = service.discard(USER, RECIPE, stored.getId());

        assertEquals(RecipeAiWorkflowStatus.CANCELLED, discarded.getStatus());
        assertNull(stored.getActiveKey());
        assertThrows(RecipeAiWorkflowException.class, () -> service.approve(USER, RECIPE, stored.getId(), 3L));
        verifyNoInteractions(visualizationJobService, narrationService);
    }

    // --- helpers -------------------------------------------------------------------------------

    private static RecipeAiWorkflowCreateRequestDTO request(String recipeText, boolean completeExperience) {
        return new RecipeAiWorkflowCreateRequestDTO(new RecipeAiTaskSelectionDTO(List.of(), completeExperience), recipeText);
    }

    private void completeGeneration(boolean applied) {
        RecipeProcessGenerationJob job = generationJobs.get(stored.getGenerationJobId());
        job.setStatus(RecipeProcessGenerationJobStatus.COMPLETED);
        job.setStage(RecipeProcessGenerationStage.COMPLETED);
        job.setResultAppliedAt(applied ? NOW : null);
    }

    private void approvedWorkflow(RecipeAiTaskType... downstream) {
        stored = new RecipeAiWorkflow();
        stored.setId("wf-approved");
        stored.setRecipeId(RECIPE);
        stored.setUserId(USER);
        stored.setSelectedTasks(List.of(downstream));
        stored.setStatus(RecipeAiWorkflowStatus.PARTIALLY_COMPLETED);
        stored.setApprovedSnapshot(new RecipeAiWorkflow.ApprovedSnapshot(3L, NOW, List.of(
                new RecipeAiWorkflow.ApprovedProcess(MAIN, "Main", List.of("s1", "s2")),
                new RecipeAiWorkflow.ApprovedProcess(SUB, "Sub", List.of("s3")))));
    }

    private static RecipeAiWorkflowResponseDTO.TaskDTO task(RecipeAiWorkflowResponseDTO dto, RecipeAiTaskType type) {
        return dto.getTasks().stream().filter(task -> task.getType() == type).findFirst().orElseThrow();
    }

    /** MAIN: one step that uses the Preparation's one flow output. */
    private static Process readyMain() {
        Process main = process(MAIN, ProcessType.MAIN);
        main.getNodes().add(stepNode("m1", Map.of("action", "add", "actionDescription", "Add the eggs",
                "actionOn", Map.of("processes", List.of(Map.of("processId", SUB, "stepId", "p1"))))));
        return main;
    }

    private static Process readyPreparation() {
        Process preparation = process(SUB, ProcessType.SUBPROCESS);
        preparation.getNodes().add(stepNode("p1", Map.of("action", "boil", "actionDescription", "Boil the eggs", "expectedOutput", "Boiled Eggs")));
        return preparation;
    }

    private static Process.ProcessNode stepNode(String id, Map<String, Object> step) {
        Process.ProcessNode node = new Process.ProcessNode();
        node.setId(id);
        node.setKind(ProcessNodeKind.STEP);
        node.setData(new LinkedHashMap<>(Map.of("step", step)));
        return node;
    }

    private static Process process(Long id, ProcessType type) {
        Process process = new Process();
        process.setId(id);
        process.setType(type);
        process.setRecipeId(RECIPE);
        process.setName(type.name());
        return process;
    }

    private RecipeProcessVisualizationService.ProcessStepPreparation preparation(Long processId) {
        List<RecipeProcessVisualizationService.StepContext> steps = new ArrayList<>();
        for (String stepId : savedStepIds.getOrDefault(processId, List.of())) {
            Process.ProcessNode node = new Process.ProcessNode();
            node.setId(stepId);
            steps.add(new RecipeProcessVisualizationService.StepContext(node, null));
        }
        return new RecipeProcessVisualizationService.ProcessStepPreparation(process(processId, ProcessType.MAIN), steps);
    }

    private static VisualizationJob vizJob(String id, VisualizationJobStatus status, int total, int succeeded) {
        VisualizationJob job = new VisualizationJob();
        job.setId(id);
        job.setStatus(status);
        job.setTotalSteps(total);
        for (int i = 0; i < total; i++) {
            VisualizationJob.StepResult result = new VisualizationJob.StepResult();
            result.setStepId("step-" + i);
            result.setSuccess(i < succeeded);
            job.getStepResults().add(result);
        }
        job.setCompletedSteps(total);
        return job;
    }

    private static StepNarrationResponseDTO narration(String stepId, String status) {
        return StepNarrationResponseDTO.builder().stepId(stepId).status(status).narratable(true).build();
    }

    /** Applies the {@code $set}/{@code $unset} of a mocked mongoTemplate update to {@link #stored}. */
    @SuppressWarnings("unchecked")
    private void apply(InvocationOnMock invocation) {
        Query query = invocation.getArgument(0, Query.class);
        Document criteria = query.getQueryObject();
        if (stored == null || !stored.getId().equals(criteria.get("_id"))) {
            return;
        }
        Object expectedStatus = criteria.get("status");
        if (expectedStatus instanceof RecipeAiWorkflowStatus status && stored.getStatus() != status) {
            return; // the conditional write lost against a concurrent change
        }
        Document update = invocation.getArgument(1, Update.class).getUpdateObject();
        Document set = update.get("$set", Document.class);
        if (set != null) {
            set.forEach((field, value) -> {
                switch (field) {
                    case "status" -> stored.setStatus((RecipeAiWorkflowStatus) value);
                    case "generationJobId" -> stored.setGenerationJobId((String) value);
                    case "approvedSnapshot" -> stored.setApprovedSnapshot((RecipeAiWorkflow.ApprovedSnapshot) value);
                    case "visualizationJobs" -> stored.setVisualizationJobs(new ArrayList<>((List<RecipeAiWorkflow.VisualizationJobRef>) value));
                    case "activeKey" -> stored.setActiveKey((String) value);
                    case "completedAt" -> stored.setCompletedAt((Instant) value);
                    case "dismissedAt" -> stored.setDismissedAt((Instant) value);
                    default -> { }
                }
            });
        }
        Document unset = update.get("$unset", Document.class);
        if (unset != null) {
            unset.keySet().forEach(field -> {
                switch (field) {
                    case "activeKey" -> stored.setActiveKey(null);
                    case "completedAt" -> stored.setCompletedAt(null);
                    case "dismissedAt" -> stored.setDismissedAt(null);
                    default -> { }
                }
            });
        }
    }
}
