package com.processVisualisation.virtualKitchen.ai.recipe.generation;

import com.processVisualisation.virtualKitchen.ai.recipe.edit.RecipeProcessEditService;
import com.processVisualisation.virtualKitchen.ai.recipe.edit.dto.EditTargetProcessDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.ProcessGenerationMode;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.RecipeProcessGenerationJobResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.RecipeProcessGenerationRequestDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.RecipeProcessGenerationResultDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.model.RecipeProcessGenerationJob;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.model.RecipeProcessGenerationJobStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.model.RecipeProcessGenerationStage;
import com.processVisualisation.virtualKitchen.common.concurrent.ThreadPoolTaskPool;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RecipeProcessGenerationJobServiceTest {

    private static final Long USER = 7L;
    private static final Long RECIPE = 100L;
    private static final String ACTIVE_KEY = "gen:7:100";

    private RecipeProcessGenerationService generationService;
    private RecipeProcessEditService editService;
    private RecipeProcessGenerationJobRepository jobRepository;
    private MongoTemplate mongoTemplate;
    private RecipeProcessGenerationJobService service;

    @BeforeEach
    void setUp() {
        generationService = mock(RecipeProcessGenerationService.class);
        editService = mock(RecipeProcessEditService.class);
        jobRepository = mock(RecipeProcessGenerationJobRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        // Conditional job writes (claim, guarded status updates) read the matched count; a real database answers 1.
        org.mockito.Mockito.when(mongoTemplate.updateFirst(org.mockito.ArgumentMatchers.any(org.springframework.data.mongodb.core.query.Query.class),
                org.mockito.ArgumentMatchers.any(org.springframework.data.mongodb.core.query.UpdateDefinition.class),
                org.mockito.ArgumentMatchers.<Class<?>>any()))
                .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));
        service = new RecipeProcessGenerationJobService(
                generationService, editService, jobRepository, mongoTemplate, new ThreadPoolTaskPool(1, "test-generation-orchestrator"));
    }

    @Test
    void startJob_noRunningJob_insertsWithActiveKeyAndStartsWork() {
        when(generationService.generate(eq(USER), anyString(), any(), any())).thenReturn(new RecipeProcessGenerationResultDTO());

        RecipeProcessGenerationJobResponseDTO started = service.startJob(USER, RECIPE, "text", "req-1");

        assertFalse(started.isReused());
        assertEquals("QUEUED", started.getStatus());
        ArgumentCaptor<RecipeProcessGenerationJob> inserted = ArgumentCaptor.forClass(RecipeProcessGenerationJob.class);
        verify(jobRepository).insert(inserted.capture());
        assertEquals(ACTIVE_KEY, inserted.getValue().getActiveKey());
        verify(generationService, timeout(5000)).generate(eq(USER), eq("text"), eq("req-1"), any());
        assertEquals(ProcessGenerationMode.CREATE, inserted.getValue().getMode());
        assertEquals("CREATE", started.getMode());
    }

    @Test
    void startJob_editMode_runsTheEditPipelineWithTheTargetProcess() {
        EditTargetProcessDTO target = new EditTargetProcessDTO(11L, "Main Process", List.of(), List.of());
        RecipeProcessGenerationRequestDTO request = new RecipeProcessGenerationRequestDTO("Remove the last step", "req-3");
        request.setMode(ProcessGenerationMode.EDIT);
        request.setTargetProcess(target);
        request.setSelectedNodeId("node-4");
        when(editService.edit(eq(USER), anyString(), any(), any(), any(), any()))
                .thenReturn(RecipeProcessGenerationResultDTO.builder().mode(ProcessGenerationMode.EDIT).build());

        RecipeProcessGenerationJobResponseDTO started = service.startJob(USER, RECIPE, request);

        assertEquals("EDIT", started.getMode());
        verify(editService, timeout(5000)).edit(eq(USER), eq("Remove the last step"), eq(target), eq("node-4"), eq("req-3"), any());
        verifyNoInteractions(generationService);
    }

    @Test
    void startJob_whileAlreadyRunning_joinsRunningJobWithoutStartingAnother() {
        RecipeProcessGenerationJob running = job("running", RecipeProcessGenerationJobStatus.IN_PROGRESS);
        when(jobRepository.findByActiveKey(ACTIVE_KEY)).thenReturn(Optional.of(running));

        RecipeProcessGenerationJobResponseDTO result = service.startJob(USER, RECIPE, "text", "req-2");

        assertTrue(result.isReused());
        assertEquals("running", result.getJobId());
        verify(jobRepository, never()).insert(any(RecipeProcessGenerationJob.class));
        verifyNoInteractions(generationService);
    }

    @Test
    void startJob_losesConcurrentInsertRace_joinsTheWinner() {
        RecipeProcessGenerationJob winner = job("winner", RecipeProcessGenerationJobStatus.QUEUED);
        when(jobRepository.findByActiveKey(ACTIVE_KEY)).thenReturn(Optional.empty(), Optional.of(winner));
        when(jobRepository.insert(any(RecipeProcessGenerationJob.class))).thenThrow(new DuplicateKeyException("activeKey"));

        RecipeProcessGenerationJobResponseDTO result = service.startJob(USER, RECIPE, "text", null);

        assertTrue(result.isReused());
        assertEquals("winner", result.getJobId());
        verifyNoInteractions(generationService);
    }

    @Test
    void runPipeline_onCompletion_clearsActiveKeySoANewJobCanStart() {
        when(generationService.generate(eq(USER), anyString(), any(), any())).thenReturn(new RecipeProcessGenerationResultDTO());

        service.startJob(USER, RECIPE, "text", null);

        ArgumentCaptor<Update> updates = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, timeout(5000).atLeast(2))
                .updateFirst(any(Query.class), updates.capture(), eq(RecipeProcessGenerationJob.class));
        Update completed = updates.getAllValues().stream()
                .filter(u -> RecipeProcessGenerationJobStatus.COMPLETED.equals(setValue(u, "status")))
                .findFirst().orElseThrow();
        Document unset = (Document) completed.getUpdateObject().get("$unset");
        assertTrue(unset != null && unset.containsKey("activeKey"));
    }

    @Test
    void getJobStatus_jobOfAnotherUserOrRecipe_isNotFound() {
        RecipeProcessGenerationJob job = job("j1", RecipeProcessGenerationJobStatus.IN_PROGRESS);
        when(jobRepository.findById("j1")).thenReturn(Optional.of(job));

        assertEquals("j1", service.getJobStatus(USER, RECIPE, "j1").getJobId());
        assertThrows(NoSuchElementException.class, () -> service.getJobStatus(99L, RECIPE, "j1"));
        assertThrows(NoSuchElementException.class, () -> service.getJobStatus(USER, 555L, "j1"));
    }

    @Test
    void findResumable_nothingRunning_offersTheUnappliedCompletedResult() {
        RecipeProcessGenerationJob done = job("done", RecipeProcessGenerationJobStatus.COMPLETED);
        done.setStage(RecipeProcessGenerationStage.COMPLETED);
        when(jobRepository.findFirstByUserIdAndRecipeIdAndStatusAndResultAppliedAtIsNullAndCompletedAtAfterOrderByCompletedAtDesc(
                eq(USER), eq(RECIPE), eq(RecipeProcessGenerationJobStatus.COMPLETED), any(Instant.class)))
                .thenReturn(Optional.of(done));

        Optional<RecipeProcessGenerationJobResponseDTO> resumable = service.findResumable(USER, RECIPE);

        assertTrue(resumable.isPresent());
        assertEquals("done", resumable.get().getJobId());
        assertEquals(100, resumable.get().getProgressPercent());
    }

    @Test
    void markResultApplied_ownedJob_setsResultAppliedAt() {
        when(jobRepository.findById("j1")).thenReturn(Optional.of(job("j1", RecipeProcessGenerationJobStatus.COMPLETED)));

        service.markResultApplied(USER, RECIPE, "j1");

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), update.capture(), eq(RecipeProcessGenerationJob.class));
        assertTrue(setValue(update.getValue(), "resultAppliedAt") instanceof Instant);
    }

    private RecipeProcessGenerationJob job(String id, RecipeProcessGenerationJobStatus status) {
        RecipeProcessGenerationJob job = new RecipeProcessGenerationJob();
        job.setId(id);
        job.setUserId(USER);
        job.setRecipeId(RECIPE);
        job.setStatus(status);
        job.setStage(RecipeProcessGenerationStage.CALLING_MODEL);
        return job;
    }

    private Object setValue(Update update, String field) {
        Document set = (Document) update.getUpdateObject().get("$set");
        return set == null ? null : set.get(field);
    }
}
