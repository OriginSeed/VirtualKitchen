package com.processVisualisation.virtualKitchen.recipe.service;

import com.processVisualisation.virtualKitchen.common.persistence.SequenceGeneratorService;
import com.processVisualisation.virtualKitchen.recipe.dto.ProcessBatchItemDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.ProcessBatchUpdateResponseDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.ProcessNodeDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.ProcessRequestDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.ProcessResponseDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.ProcessUpdateDTO;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeRevisionConflictException;
import com.processVisualisation.virtualKitchen.recipe.mapper.ProcessMapper;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessNodeKind;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessType;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import com.processVisualisation.virtualKitchen.recipe.model.Visibility;
import com.processVisualisation.virtualKitchen.recipe.repository.ProcessRepository;
import com.processVisualisation.virtualKitchen.recipe.repository.RecipeTemplateRepository;
import com.processVisualisation.virtualKitchen.recipe.validation.ProcessValidationException;
import com.processVisualisation.virtualKitchen.recipe.validation.ProcessValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service-level tests for {@link ProcessService}, backed by
 * Mockito-stubbed repositories over simple in-memory maps (no embedded
 * Mongo), matching this project's existing service test style (see e.g.
 * {@code VisualizationJobServiceTest}). Uses a real {@link ProcessValidator}
 * (constructed over the same fake repositories) rather than mocking it away,
 * so validation rules are exercised end-to-end through the service.
 */
class ProcessServiceTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;

    private final Map<Long, Process> processStore = new ConcurrentHashMap<>();
    private final Map<Long, RecipeTemplate> recipeStore = new ConcurrentHashMap<>();

    private ProcessRepository processRepository;
    private RecipeTemplateRepository recipeTemplateRepository;
    private ProcessService service;

    private Long recipeId;
    private Long otherRecipeId;

    @BeforeEach
    void setUp() {
        processStore.clear();
        recipeStore.clear();

        processRepository = mock(ProcessRepository.class);
        recipeTemplateRepository = mock(RecipeTemplateRepository.class);
        SequenceGeneratorService sequenceGeneratorService = mock(SequenceGeneratorService.class);

        AtomicLong idCounter = new AtomicLong(1000);
        when(sequenceGeneratorService.generateSequence(anyString())).thenAnswer(inv -> idCounter.incrementAndGet());

        when(processRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(processStore.get(inv.<Long>getArgument(0))));
        when(processRepository.save(any())).thenAnswer(inv -> {
            Process process = inv.getArgument(0);
            processStore.put(process.getId(), process);
            return process;
        });
        when(processRepository.saveAll(any())).thenAnswer(inv -> {
            List<Process> processes = new ArrayList<>();
            Iterable<Process> given = inv.getArgument(0);
            for (Process process : given) {
                processStore.put(process.getId(), process);
                processes.add(process);
            }
            return processes;
        });
        when(processRepository.findByRecipeId(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return processStore.values().stream()
                    .filter(p -> id.equals(p.getRecipeId()))
                    .collect(Collectors.toList());
        });
        when(processRepository.findByRecipeIdAndType(anyLong(), any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            ProcessType type = inv.getArgument(1);
            return processStore.values().stream()
                    .filter(p -> id.equals(p.getRecipeId()) && p.getType() == type)
                    .collect(Collectors.toList());
        });
        doAnswer(inv -> {
            processStore.remove(inv.<Long>getArgument(0));
            return null;
        }).when(processRepository).deleteById(anyLong());

        when(recipeTemplateRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(recipeStore.get(inv.<Long>getArgument(0))));
        // Revision claims: same conditional semantics as the real @Query/@Update methods, over recipeStore.
        when(recipeTemplateRepository.claimProcessRevision(anyLong(), anyLong())).thenAnswer(inv ->
                compareAndBumpRevision(inv.getArgument(0), inv.<Long>getArgument(1), 1));
        when(recipeTemplateRepository.claimInitialProcessRevision(anyLong())).thenAnswer(inv ->
                compareAndBumpRevision(inv.getArgument(0), 0L, 1));
        when(recipeTemplateRepository.releaseProcessRevision(anyLong(), anyLong())).thenAnswer(inv ->
                compareAndBumpRevision(inv.getArgument(0), inv.<Long>getArgument(1), -1));
        when(recipeTemplateRepository.incrementProcessRevision(anyLong())).thenAnswer(inv -> {
            RecipeTemplate recipe = recipeStore.get(inv.<Long>getArgument(0));
            return compareAndBumpRevision(recipe.getId(), revisionOf(recipe), 1);
        });

        ProcessValidator validator = new ProcessValidator(processRepository, recipeTemplateRepository);
        ProcessMapper mapper = new ProcessMapper();
        service = new ProcessService(processRepository, recipeTemplateRepository, sequenceGeneratorService, validator, mapper);

        recipeId = seedRecipe(OWNER_ID, Visibility.PRIVATE);
        otherRecipeId = seedRecipe(OTHER_USER_ID, Visibility.PRIVATE);
    }

    @Test
    void create_main_persistsAndReturnsMainProcess() {
        ProcessResponseDTO created = service.create(recipeId, OWNER_ID, request(ProcessType.MAIN, "Main"));

        assertEquals(ProcessType.MAIN, created.getType());
        assertEquals(recipeId, created.getRecipeId());
        assertTrue(processStore.containsKey(created.getId()));
    }

    @Test
    void create_marksTheRecipeAsUpdated() {
        service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Prep"));

        verify(recipeTemplateRepository).touchUpdatedAt(org.mockito.ArgumentMatchers.eq(recipeId), any());
    }

    @Test
    void create_rejected_leavesTheRecipeUntouched() {
        assertThrows(RecipeAccessDeniedException.class,
                () -> service.create(recipeId, OTHER_USER_ID, request(ProcessType.MAIN, "Main")));

        verify(recipeTemplateRepository, never()).touchUpdatedAt(any(), any());
    }

    @Test
    void create_subprocess_persistsAndReturnsSubprocess() {
        ProcessResponseDTO created = service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Prepare Chicken"));

        assertEquals(ProcessType.SUBPROCESS, created.getType());
        assertEquals("Prepare Chicken", created.getName());
    }

    @Test
    void create_nonOwner_isRejected() {
        assertThrows(RecipeAccessDeniedException.class,
                () -> service.create(recipeId, OTHER_USER_ID, request(ProcessType.MAIN, "Main")));
    }

    @Test
    void create_secondMainProcess_isRejected() {
        service.create(recipeId, OWNER_ID, request(ProcessType.MAIN, "Main"));

        assertThrows(ProcessValidationException.class,
                () -> service.create(recipeId, OWNER_ID, request(ProcessType.MAIN, "Main 2")));
    }

    @Test
    void create_invalidRequest_unknownRecipe_isRejected() {
        assertThrows(NoSuchElementException.class,
                () -> service.create(999L, OWNER_ID, request(ProcessType.MAIN, "Main")));
    }

    @Test
    void get_returnsMatchingProcess() {
        ProcessResponseDTO created = service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Prep"));

        ProcessResponseDTO fetched = service.get(recipeId, created.getId(), OWNER_ID);

        assertEquals(created.getId(), fetched.getId());
        assertEquals("Prep", fetched.getName());
    }

    @Test
    void get_processFromAnotherRecipe_isNotFound() {
        ProcessResponseDTO created = service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Prep"));

        assertThrows(NoSuchElementException.class, () -> service.get(otherRecipeId, created.getId(), OTHER_USER_ID));
    }

    @Test
    void get_privateRecipe_nonOwner_isForbidden() {
        ProcessResponseDTO created = service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Prep"));

        assertThrows(RecipeAccessDeniedException.class, () -> service.get(recipeId, created.getId(), OTHER_USER_ID));
    }

    @Test
    void get_publicRecipe_anyoneCanRead() {
        Long publicRecipeId = seedRecipe(OWNER_ID, Visibility.PUBLIC);
        ProcessResponseDTO created = service.create(publicRecipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Prep"));

        ProcessResponseDTO fetched = service.get(publicRecipeId, created.getId(), OTHER_USER_ID);

        assertEquals(created.getId(), fetched.getId());
    }

    @Test
    void get_publicRecipe_unauthenticatedCanRead() {
        Long publicRecipeId = seedRecipe(OWNER_ID, Visibility.PUBLIC);
        ProcessResponseDTO created = service.create(publicRecipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Prep"));

        ProcessResponseDTO fetched = service.get(publicRecipeId, created.getId(), null);

        assertEquals(created.getId(), fetched.getId());
    }

    @Test
    void listByRecipe_returnsOnlyThatRecipesProcesses() {
        service.create(recipeId, OWNER_ID, request(ProcessType.MAIN, "Main"));
        service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Sub"));
        service.create(otherRecipeId, OTHER_USER_ID, request(ProcessType.MAIN, "Other Main"));

        List<ProcessResponseDTO> processes = service.listByRecipe(recipeId, OWNER_ID);

        assertEquals(2, processes.size());
    }

    @Test
    void update_replacesNameDescriptionNodesAndEdges() {
        ProcessResponseDTO created = service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Prep"));

        ProcessUpdateDTO update = new ProcessUpdateDTO();
        update.setName("Prep v2");
        update.setDescription("updated");
        update.setNodes(List.of(stepNodeDTO("n1")));

        ProcessResponseDTO updated = service.update(recipeId, created.getId(), OWNER_ID, update);

        assertEquals("Prep v2", updated.getName());
        assertEquals(1, updated.getNodes().size());
        assertEquals("n1", updated.getNodes().get(0).getId());
    }

    @Test
    void update_invalidGraph_isRejected() {
        ProcessResponseDTO created = service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Prep"));

        ProcessUpdateDTO update = new ProcessUpdateDTO();
        update.setName("Prep");
        // a STEP node with no data is structurally invalid
        ProcessNodeDTO invalidNode = ProcessNodeDTO.builder().id("n1").kind(ProcessNodeKind.STEP).build();
        update.setNodes(List.of(invalidNode));

        assertThrows(ProcessValidationException.class, () -> service.update(recipeId, created.getId(), OWNER_ID, update));
    }

    @Test
    void update_nonOwner_isRejected() {
        ProcessResponseDTO created = service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Prep"));
        ProcessUpdateDTO update = new ProcessUpdateDTO();
        update.setName("hijacked");

        assertThrows(RecipeAccessDeniedException.class, () -> service.update(recipeId, created.getId(), OTHER_USER_ID, update));
    }

    @Test
    void updateAll_savesEveryListedProcessInOneCall() {
        ProcessResponseDTO main = service.create(recipeId, OWNER_ID, request(ProcessType.MAIN, "Main"));
        ProcessResponseDTO sub = service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Cut Vegetables"));

        List<ProcessResponseDTO> updated = service.updateAll(recipeId, OWNER_ID, List.of(
                batchItem(main.getId(), "Main", List.of(stepNodeDTO("n1"))),
                batchItem(sub.getId(), "Cut Vegetables v2", List.of(stepNodeDTO("n2")))
        ), null).getProcesses();

        assertEquals(2, updated.size());
        assertEquals("Main", processStore.get(main.getId()).getName());
        assertEquals(1, processStore.get(main.getId()).getNodes().size());
        assertEquals("Cut Vegetables v2", processStore.get(sub.getId()).getName());
        assertEquals(1, processStore.get(sub.getId()).getNodes().size());
    }

    @Test
    void updateAll_oneInvalidProcess_rejectsWholeBatchWithoutPersistingAny() {
        ProcessResponseDTO main = service.create(recipeId, OWNER_ID, request(ProcessType.MAIN, "Main"));
        ProcessResponseDTO sub = service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Cut Vegetables"));

        ProcessNodeDTO invalidNode = ProcessNodeDTO.builder().id("n1").kind(ProcessNodeKind.STEP).build();
        List<ProcessBatchItemDTO> items = List.of(
                batchItem(main.getId(), "Main v2", List.of(stepNodeDTO("n1"))),
                batchItem(sub.getId(), "Cut Vegetables v2", List.of(invalidNode))
        );

        assertThrows(ProcessValidationException.class, () -> service.updateAll(recipeId, OWNER_ID, items, null));
        // The fake repository's findById returns the same live object instance rather than a fresh
        // copy (unlike real Spring Data Mongo), so an in-place field mutation is visible even before
        // any save call — asserting on that would test an artifact of the fake, not real behavior.
        // What actually matters is that saveAll (the only place anything is persisted) never ran.
        verify(processRepository, never()).saveAll(any());
    }

    @Test
    void updateAll_nonOwner_isRejected() {
        ProcessResponseDTO sub = service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Prep"));
        List<ProcessBatchItemDTO> items = List.of(batchItem(sub.getId(), "hijacked", null));

        assertThrows(RecipeAccessDeniedException.class, () -> service.updateAll(recipeId, OTHER_USER_ID, items, null));
    }

    @Test
    void updateAll_processFromAnotherRecipe_isRejected() {
        ProcessResponseDTO otherRecipeProcess = service.create(otherRecipeId, OTHER_USER_ID, request(ProcessType.SUBPROCESS, "Prep"));
        List<ProcessBatchItemDTO> items = List.of(batchItem(otherRecipeProcess.getId(), "hijacked", null));

        assertThrows(NoSuchElementException.class, () -> service.updateAll(recipeId, OWNER_ID, items, null));
    }

    @Test
    void updateAll_withCurrentBaseRevision_savesAndBumpsRevision() {
        ProcessResponseDTO main = service.create(recipeId, OWNER_ID, request(ProcessType.MAIN, "Main"));
        ProcessResponseDTO sub = service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Sauce"));

        ProcessBatchUpdateResponseDTO first = service.updateAll(recipeId, OWNER_ID, List.of(
                batchItem(main.getId(), "Main", List.of(stepNodeDTO("n1"))),
                batchItem(sub.getId(), "Sauce", List.of(stepNodeDTO("n2")))
        ), 0L);
        ProcessBatchUpdateResponseDTO second = service.updateAll(recipeId, OWNER_ID, List.of(
                batchItem(main.getId(), "Main v2", List.of(stepNodeDTO("n1"))),
                batchItem(sub.getId(), "Sauce v2", List.of(stepNodeDTO("n2"), stepNodeDTO("n3")))
        ), first.getRevision());

        assertEquals(1L, first.getRevision());
        assertEquals(2L, second.getRevision());
        assertEquals(2L, recipeStore.get(recipeId).getProcessRevision());
        // The complete multi-process snapshot landed in one save.
        assertEquals("Main v2", processStore.get(main.getId()).getName());
        assertEquals(2, processStore.get(sub.getId()).getNodes().size());
        assertEquals(2, second.getProcesses().size());
    }

    @Test
    void updateAll_withStaleBaseRevision_isRejectedAsConflictWithoutPersisting() {
        ProcessResponseDTO main = service.create(recipeId, OWNER_ID, request(ProcessType.MAIN, "Main"));
        // Device A saves revision 0 -> 1.
        service.updateAll(recipeId, OWNER_ID, List.of(batchItem(main.getId(), "Main (A)", List.of(stepNodeDTO("n1")))), 0L);

        // Device B still holds revision 0.
        List<ProcessBatchItemDTO> stale = List.of(batchItem(main.getId(), "Main (B)", List.of(stepNodeDTO("n1"))));
        assertThrows(RecipeRevisionConflictException.class, () -> service.updateAll(recipeId, OWNER_ID, stale, 0L));

        assertEquals(1L, recipeStore.get(recipeId).getProcessRevision());
        verify(processRepository, times(1)).saveAll(any());
    }

    @Test
    void updateAll_withoutBaseRevision_keepsLastWriteWinsButStillBumpsRevision() {
        ProcessResponseDTO main = service.create(recipeId, OWNER_ID, request(ProcessType.MAIN, "Main"));
        recipeStore.get(recipeId).setProcessRevision(5L);

        ProcessBatchUpdateResponseDTO saved = service.updateAll(recipeId, OWNER_ID,
                List.of(batchItem(main.getId(), "Main", List.of(stepNodeDTO("n1")))), null);

        assertEquals(6L, saved.getRevision());
    }

    @Test
    void updateAll_invalidBatch_doesNotClaimARevision() {
        ProcessResponseDTO main = service.create(recipeId, OWNER_ID, request(ProcessType.MAIN, "Main"));
        ProcessNodeDTO invalidNode = ProcessNodeDTO.builder().id("n1").kind(ProcessNodeKind.STEP).build();

        assertThrows(ProcessValidationException.class, () -> service.updateAll(recipeId, OWNER_ID,
                List.of(batchItem(main.getId(), "Main", List.of(invalidNode))), 0L));

        assertEquals(0L, revisionOf(recipeStore.get(recipeId)));
    }

    @Test
    void updateAll_saveFailureAfterClaim_releasesRevisionSoTheRetrySucceeds() {
        ProcessResponseDTO main = service.create(recipeId, OWNER_ID, request(ProcessType.MAIN, "Main"));
        List<ProcessBatchItemDTO> items = List.of(batchItem(main.getId(), "Main", List.of(stepNodeDTO("n1"))));
        doThrow(new IllegalStateException("mongo down")).when(processRepository).saveAll(any());

        assertThrows(IllegalStateException.class, () -> service.updateAll(recipeId, OWNER_ID, items, 0L));
        assertEquals(0L, revisionOf(recipeStore.get(recipeId)));

        doAnswer(inv -> inv.getArgument(0)).when(processRepository).saveAll(any());
        assertEquals(1L, service.updateAll(recipeId, OWNER_ID, items, 0L).getRevision());
    }

    @Test
    void delete_subprocess_removesIt() {
        ProcessResponseDTO created = service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Prep"));

        service.delete(recipeId, created.getId(), OWNER_ID);

        assertFalse(processStore.containsKey(created.getId()));
    }

    @Test
    void delete_referencedMainProcess_isRejected() {
        ProcessResponseDTO main = service.create(recipeId, OWNER_ID, request(ProcessType.MAIN, "Main"));
        RecipeTemplate recipe = recipeStore.get(recipeId);
        recipe.setMainProcessId(main.getId());

        assertThrows(ProcessValidationException.class, () -> service.delete(recipeId, main.getId(), OWNER_ID));
        assertTrue(processStore.containsKey(main.getId()), "the referenced MAIN process must not be deleted");
    }

    @Test
    void delete_unreferencedMainProcess_succeeds() {
        // mainProcessId was never assigned on the recipe, so nothing references this MAIN process yet.
        ProcessResponseDTO main = service.create(recipeId, OWNER_ID, request(ProcessType.MAIN, "Main"));

        service.delete(recipeId, main.getId(), OWNER_ID);

        assertFalse(processStore.containsKey(main.getId()));
    }

    @Test
    void copy_subprocessWithinSameRecipe_createsIndependentCopy() {
        ProcessResponseDTO source = service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Prepare Chicken"));
        ProcessUpdateDTO update = new ProcessUpdateDTO();
        update.setName("Prepare Chicken");
        update.setNodes(List.of(stepNodeDTO("n1")));
        service.update(recipeId, source.getId(), OWNER_ID, update);

        ProcessResponseDTO copy = service.copy(recipeId, source.getId(), OWNER_ID);

        assertNotEquals(source.getId(), copy.getId());
        assertEquals("Prepare Chicken (copy)", copy.getName());
        assertEquals(1, copy.getNodes().size());
        assertNotEquals("n1", copy.getNodes().get(0).getId(), "copied node ids must be regenerated, not reused");
        assertEquals(recipeId, copy.getRecipeId());
    }

    @Test
    void copy_mainProcess_isRejected() {
        ProcessResponseDTO main = service.create(recipeId, OWNER_ID, request(ProcessType.MAIN, "Main"));

        assertThrows(ProcessValidationException.class, () -> service.copy(recipeId, main.getId(), OWNER_ID));
    }

    @Test
    void copy_crossRecipe_isRejected() {
        ProcessResponseDTO source = service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Prep"));

        // otherRecipeId is a different recipe than the one source belongs to
        assertThrows(ProcessValidationException.class, () -> service.copy(otherRecipeId, source.getId(), OTHER_USER_ID));
    }

    @Test
    void copy_nonOwner_isRejected() {
        ProcessResponseDTO source = service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Prep"));

        assertThrows(RecipeAccessDeniedException.class, () -> service.copy(recipeId, source.getId(), OTHER_USER_ID));
    }

    @Test
    void copyAllToRecipe_copiesMainAndSubprocessesWithRemappedIdsAndReferences() {
        Long sourceRecipeId = seedRecipe(OWNER_ID, Visibility.PUBLIC);
        Long targetRecipeId = seedRecipe(OTHER_USER_ID, Visibility.PRIVATE);

        Process sub = saveProcess(sourceRecipeId, ProcessType.SUBPROCESS, "Make Sauce",
                List.of(stepNode("s1", Map.of("step", Map.of("action", "stir")))), List.of());
        Process main = saveProcess(sourceRecipeId, ProcessType.MAIN, "Main",
                List.of(
                        stepNode("m1", stepData(sub.getId(), 999_999L)),
                        stepNode("m2", Map.of("step", Map.of("action", "serve")))),
                List.of(edge("e1", "m1", "m2")));

        RecipeProcessCopyResult result = service.copyAllToRecipe(sourceRecipeId, targetRecipeId);

        Long copiedMainId = result.processIdMap().get(main.getId());
        Long copiedSubId = result.processIdMap().get(sub.getId());
        assertEquals(copiedMainId, result.mainProcessId());
        assertNotEquals(main.getId(), copiedMainId);
        assertNotEquals(sub.getId(), copiedSubId);

        Process copiedMain = processStore.get(copiedMainId);
        Process copiedSub = processStore.get(copiedSubId);
        assertEquals(targetRecipeId, copiedMain.getRecipeId());
        assertEquals(targetRecipeId, copiedSub.getRecipeId());
        assertEquals(ProcessType.MAIN, copiedMain.getType());
        assertEquals("Make Sauce", copiedSub.getName(), "a recipe copy keeps process names unchanged");

        // Node ids regenerated, and the edge follows them.
        Process.ProcessNode copiedStep = copiedMain.getNodes().get(0);
        assertNotEquals("m1", copiedStep.getId());
        assertEquals(copiedStep.getId(), copiedMain.getEdges().get(0).getSource());
        assertEquals(copiedMain.getNodes().get(1).getId(), copiedMain.getEdges().get(0).getTarget());
        // ...and are reported, so data keyed by step id (narration) can follow the copy.
        assertEquals(copiedStep.getId(), result.nodeIdMaps().get(main.getId()).get("m1"));
        assertEquals(copiedSub.getNodes().get(0).getId(), result.nodeIdMaps().get(sub.getId()).get("s1"));

        // Action On now points at the copied subprocess; the reference outside the source recipe is dropped.
        assertEquals(List.of(copiedSubId), actionOnProcessIds(copiedStep));
        // The source is untouched.
        assertEquals(List.of(sub.getId(), 999_999L), actionOnProcessIds(processStore.get(main.getId()).getNodes().get(0)));
        assertEquals(2, processRepository.findByRecipeId(sourceRecipeId).size());
    }

    @Test
    void copyAllToRecipe_copiedDataIsIndependentOfSource() {
        Long sourceRecipeId = seedRecipe(OWNER_ID, Visibility.PUBLIC);
        Long targetRecipeId = seedRecipe(OTHER_USER_ID, Visibility.PRIVATE);
        Process main = saveProcess(sourceRecipeId, ProcessType.MAIN, "Main",
                List.of(stepNode("m1", stepData())), List.of());

        RecipeProcessCopyResult result = service.copyAllToRecipe(sourceRecipeId, targetRecipeId);

        Process copiedMain = processStore.get(result.mainProcessId());
        @SuppressWarnings("unchecked")
        Map<String, Object> copiedStep = (Map<String, Object>) copiedMain.getNodes().get(0).getData().get("step");
        copiedStep.put("action", "changed-in-copy");

        @SuppressWarnings("unchecked")
        Map<String, Object> sourceStep = (Map<String, Object>) processStore.get(main.getId()).getNodes().get(0).getData().get("step");
        assertEquals("chop", sourceStep.get("action"));
    }

    @Test
    void copyAllToRecipe_sourceWithoutProcesses_copiesNothing() {
        Long sourceRecipeId = seedRecipe(OWNER_ID, Visibility.PUBLIC);
        Long targetRecipeId = seedRecipe(OTHER_USER_ID, Visibility.PRIVATE);

        RecipeProcessCopyResult result = service.copyAllToRecipe(sourceRecipeId, targetRecipeId);

        assertTrue(result.processIdMap().isEmpty());
        assertEquals(null, result.mainProcessId());
        assertTrue(processRepository.findByRecipeId(targetRecipeId).isEmpty());
    }

    @Test
    void copyAllToRecipe_invalidSourceProcess_savesNothing() {
        Long sourceRecipeId = seedRecipe(OWNER_ID, Visibility.PUBLIC);
        Long targetRecipeId = seedRecipe(OTHER_USER_ID, Visibility.PRIVATE);
        saveProcess(sourceRecipeId, ProcessType.MAIN, "Main", List.of(stepNode("m1", stepData())), List.of());
        // A STEP with no data fails validation.
        saveProcess(sourceRecipeId, ProcessType.SUBPROCESS, "Broken", List.of(stepNode("b1", Map.of())), List.of());

        assertThrows(ProcessValidationException.class, () -> service.copyAllToRecipe(sourceRecipeId, targetRecipeId));
        assertTrue(processRepository.findByRecipeId(targetRecipeId).isEmpty());
    }

    // --- step-output references (data.step.actionOn.steps[].stepId) ---

    @Test
    void update_stepOutputReference_isPersistedAndReloadedUnchanged() {
        ProcessResponseDTO created = service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Eggs"));
        ProcessUpdateDTO update = new ProcessUpdateDTO();
        update.setName("Eggs");
        update.setNodes(List.of(
                recipeStepDTO("boil", "boiled eggs"),
                recipeStepDTO("fry", "fried eggs with onions", "boil")));
        update.setEdges(List.of(edgeDTO("e1", "boil", "fry")));

        service.update(recipeId, created.getId(), OWNER_ID, update);
        ProcessResponseDTO reloaded = service.get(recipeId, created.getId(), OWNER_ID);

        ProcessNodeDTO fry = reloaded.getNodes().stream().filter(n -> n.getId().equals("fry")).findFirst().orElseThrow();
        assertEquals(List.of("boil"), stepOutputRefs(fry.getData()), "the reference is stored by the source step's stable node id");
    }

    @Test
    void update_selfOrUnavailableStepOutputReference_isRejected() {
        ProcessResponseDTO created = service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Eggs"));

        ProcessUpdateDTO self = new ProcessUpdateDTO();
        self.setName("Eggs");
        self.setNodes(List.of(recipeStepDTO("boil", "boiled eggs", "boil")));
        assertThrows(ProcessValidationException.class, () -> service.update(recipeId, created.getId(), OWNER_ID, self));

        ProcessUpdateDTO later = new ProcessUpdateDTO();
        later.setName("Eggs");
        later.setNodes(List.of(recipeStepDTO("boil", "boiled eggs", "fry"), recipeStepDTO("fry", "fried")));
        assertThrows(ProcessValidationException.class, () -> service.update(recipeId, created.getId(), OWNER_ID, later));
    }

    @Test
    void copy_remapsStepOutputReferencesToTheCopiedNodes() {
        ProcessResponseDTO source = service.create(recipeId, OWNER_ID, request(ProcessType.SUBPROCESS, "Eggs"));
        ProcessUpdateDTO update = new ProcessUpdateDTO();
        update.setName("Eggs");
        update.setNodes(List.of(recipeStepDTO("boil", "boiled eggs"), recipeStepDTO("fry", "fried eggs", "boil")));
        update.setEdges(List.of(edgeDTO("e1", "boil", "fry")));
        service.update(recipeId, source.getId(), OWNER_ID, update);

        ProcessResponseDTO copy = service.copy(recipeId, source.getId(), OWNER_ID);

        String copiedBoilId = copy.getNodes().get(0).getId();
        assertNotEquals("boil", copiedBoilId);
        assertEquals(List.of(copiedBoilId), stepOutputRefs(copy.getNodes().get(1).getData()));
        assertEquals(List.of("boil"), stepOutputRefs(processStore.get(source.getId()).getNodes().get(1).getData()),
                "remapping the copy must not touch the source process");
    }

    @Test
    void copyAllToRecipe_remapsStepOutputReferencesToTheCopiedNodes() {
        Long sourceRecipeId = seedRecipe(OWNER_ID, Visibility.PUBLIC);
        Long targetRecipeId = seedRecipe(OTHER_USER_ID, Visibility.PRIVATE);
        saveProcess(sourceRecipeId, ProcessType.MAIN, "Main",
                List.of(stepNode("m1", recipeStepData("marinated chicken")), stepNode("m2", recipeStepData("curry", "m1"))),
                List.of(edge("e1", "m1", "m2")));

        RecipeProcessCopyResult result = service.copyAllToRecipe(sourceRecipeId, targetRecipeId);

        Process copiedMain = processStore.get(result.mainProcessId());
        assertEquals(List.of(copiedMain.getNodes().get(0).getId()), stepOutputRefs(copiedMain.getNodes().get(1).getData()));
    }

    private static Map<String, Object> recipeStepData(String expectedOutput, String... referencedStepIds) {
        List<Object> steps = new ArrayList<>();
        for (String stepId : referencedStepIds) steps.add(new java.util.LinkedHashMap<>(Map.of("stepId", stepId)));
        Map<String, Object> actionOn = new java.util.LinkedHashMap<>();
        actionOn.put("ingredients", new ArrayList<>());
        actionOn.put("processes", new ArrayList<>());
        actionOn.put("steps", steps);
        Map<String, Object> step = new java.util.LinkedHashMap<>();
        step.put("action", "fry");
        step.put("expectedOutput", expectedOutput);
        step.put("actionOn", actionOn);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("step", step);
        return data;
    }

    private static ProcessNodeDTO recipeStepDTO(String id, String expectedOutput, String... referencedStepIds) {
        return ProcessNodeDTO.builder().id(id).kind(ProcessNodeKind.STEP).type("processStepNode")
                .data(recipeStepData(expectedOutput, referencedStepIds)).build();
    }

    private static com.processVisualisation.virtualKitchen.recipe.dto.ProcessEdgeDTO edgeDTO(String id, String source, String target) {
        return com.processVisualisation.virtualKitchen.recipe.dto.ProcessEdgeDTO.builder().id(id).source(source).target(target).build();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stepOutputRefs(Map<String, Object> data) {
        Map<String, Object> step = (Map<String, Object>) data.get("step");
        Map<String, Object> actionOn = (Map<String, Object>) step.get("actionOn");
        return ((List<Map<String, Object>>) actionOn.get("steps")).stream()
                .map(entry -> String.valueOf(entry.get("stepId")))
                .collect(Collectors.toList());
    }

    private Process saveProcess(Long ownerRecipeId, ProcessType type, String name,
                                List<Process.ProcessNode> nodes, List<Process.ProcessEdge> edges) {
        Process process = new Process();
        process.setId(5000L + processStore.size());
        process.setRecipeId(ownerRecipeId);
        process.setType(type);
        process.setName(name);
        process.setNodes(new ArrayList<>(nodes));
        process.setEdges(new ArrayList<>(edges));
        processStore.put(process.getId(), process);
        return process;
    }

    private static Process.ProcessNode stepNode(String id, Map<String, Object> data) {
        Process.ProcessNode node = new Process.ProcessNode();
        node.setId(id);
        node.setKind(ProcessNodeKind.STEP);
        node.setType("processStepNode");
        node.setData(new java.util.LinkedHashMap<>(data));
        return node;
    }

    private static Map<String, Object> stepData(Long... subprocessIds) {
        List<Object> references = new ArrayList<>();
        for (Long subprocessId : subprocessIds) {
            references.add(new java.util.LinkedHashMap<>(Map.of("processId", subprocessId)));
        }
        Map<String, Object> actionOn = new java.util.LinkedHashMap<>();
        actionOn.put("ingredients", new ArrayList<>());
        actionOn.put("processes", references);
        Map<String, Object> step = new java.util.LinkedHashMap<>();
        step.put("action", "chop");
        step.put("actionOn", actionOn);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("step", step);
        return data;
    }

    @SuppressWarnings("unchecked")
    private static List<Long> actionOnProcessIds(Process.ProcessNode node) {
        Map<String, Object> step = (Map<String, Object>) node.getData().get("step");
        Map<String, Object> actionOn = (Map<String, Object>) step.get("actionOn");
        return ((List<Map<String, Object>>) actionOn.get("processes")).stream()
                .map(entry -> ((Number) entry.get("processId")).longValue())
                .collect(Collectors.toList());
    }

    private static Process.ProcessEdge edge(String id, String source, String target) {
        Process.ProcessEdge edge = new Process.ProcessEdge();
        edge.setId(id);
        edge.setSource(source);
        edge.setTarget(target);
        return edge;
    }

    private Long seedRecipe(Long ownerId, Visibility visibility) {
        long id = 100 + recipeStore.size();
        RecipeTemplate recipe = new RecipeTemplate();
        recipe.setId(id);
        recipe.setName("Recipe " + id);
        recipe.setCreatedBy(ownerId);
        recipe.setVisibility(visibility);
        recipeStore.put(id, recipe);
        return id;
    }

    private static ProcessRequestDTO request(ProcessType type, String name) {
        ProcessRequestDTO dto = new ProcessRequestDTO();
        dto.setType(type);
        dto.setName(name);
        return dto;
    }

    private static ProcessNodeDTO stepNodeDTO(String id) {
        return ProcessNodeDTO.builder().id(id).kind(ProcessNodeKind.STEP).data(Map.of("action", "chop")).build();
    }

    private static ProcessBatchItemDTO batchItem(Long processId, String name, List<ProcessNodeDTO> nodes) {
        ProcessBatchItemDTO item = new ProcessBatchItemDTO();
        item.setProcessId(processId);
        item.setName(name);
        item.setNodes(nodes);
        return item;
    }

    private static long revisionOf(RecipeTemplate recipe) {
        return recipe.getProcessRevision() != null ? recipe.getProcessRevision() : 0L;
    }

    private long compareAndBumpRevision(Long id, Long expected, long delta) {
        RecipeTemplate recipe = recipeStore.get(id);
        if (recipe == null || revisionOf(recipe) != expected) {
            return 0L;
        }
        recipe.setProcessRevision(revisionOf(recipe) + delta);
        return 1L;
    }
}
