package com.processVisualisation.virtualKitchen.recipe.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.processVisualisation.virtualKitchen.common.logging.LogCapture;
import com.processVisualisation.virtualKitchen.common.persistence.SequenceGeneratorService;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeTemplateRequestDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.NutritionInfoDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.ProcessResponseDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeDetailResponseDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeIngredientDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeTemplateResponseDTO;
import com.processVisualisation.virtualKitchen.recipe.event.RecipeCopiedEvent;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException;
import com.processVisualisation.virtualKitchen.recipe.mapper.RecipeTemplateMapper;
import com.processVisualisation.virtualKitchen.recipe.model.NutritionInfo;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessType;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeIngredient;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import com.processVisualisation.virtualKitchen.recipe.model.Visibility;
import com.processVisualisation.virtualKitchen.recipe.repository.RecipeTemplateRepository;
import com.processVisualisation.virtualKitchen.recipe.validation.ProcessValidationException;
import com.processVisualisation.virtualKitchen.store.units.UnitType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service-level tests for the new Recipe Tool methods added to
 * {@link RecipeTemplateService} (recipe details, ingredients, nutrition,
 * main process — including concurrent-call handling — and the Process-model recipe copy). Pre-existing methods
 * (create/update/delete) are not re-tested here — they were untested before
 * this change and are untouched by it. {@link ProcessService} is mocked so these tests exercise
 * only the recipe-level orchestration/ownership/visibility logic, not
 * {@link ProcessService} itself (covered separately by
 * {@code ProcessServiceTest}). Uses {@link ReflectionTestUtils} to
 * inject the class's existing field-injected dependencies, since this
 * pre-existing class is not being switched to constructor injection here.
 */
class RecipeTemplateServiceTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;

    private final Map<Long, RecipeTemplate> recipeStore = new HashMap<>();

    private ProcessService processService;
    private ApplicationEventPublisher eventPublisher;
    private RecipeTemplateService service;

    @BeforeEach
    void setUp() {
        recipeStore.clear();

        RecipeTemplateRepository repo = mock(RecipeTemplateRepository.class);
        when(repo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(recipeStore.get(inv.<Long>getArgument(0))));
        when(repo.save(any())).thenAnswer(inv -> {
            RecipeTemplate recipe = inv.getArgument(0);
            recipeStore.put(recipe.getId(), recipe);
            return recipe;
        });

        when(repo.linkMainProcessIfUnset(anyLong(), anyLong())).thenAnswer(inv -> {
            RecipeTemplate recipe = recipeStore.get(inv.<Long>getArgument(0));
            if (recipe == null || recipe.getMainProcessId() != null) {
                return 0L;
            }
            recipe.setMainProcessId(inv.getArgument(1));
            return 1L;
        });
        doAnswer(inv -> recipeStore.remove(inv.<Long>getArgument(0))).when(repo).deleteById(anyLong());

        processService = mock(ProcessService.class);
        SequenceGeneratorService seq = mock(SequenceGeneratorService.class);
        AtomicLong idCounter = new AtomicLong(500);
        when(seq.generateSequence(anyString())).thenAnswer(inv -> idCounter.incrementAndGet());

        service = new RecipeTemplateService();
        ReflectionTestUtils.setField(service, "repo", repo);
        ReflectionTestUtils.setField(service, "mapper", new RecipeTemplateMapper());
        ReflectionTestUtils.setField(service, "seq", seq);
        ReflectionTestUtils.setField(service, "processService", processService);
        eventPublisher = mock(ApplicationEventPublisher.class);
        ReflectionTestUtils.setField(service, "eventPublisher", eventPublisher);
    }

    @Test
    void getRecipeDetails_includesIngredientsNutritionAndMainProcessId() {
        Long recipeId = seedRecipe(OWNER_ID, Visibility.PRIVATE);
        RecipeTemplate recipe = recipeStore.get(recipeId);

        RecipeIngredient ingredient = new RecipeIngredient();
        ingredient.setIngredientId(5L);
        ingredient.setQuantity(2.0);
        ingredient.setUnit(UnitType.COUNT);
        recipe.setIngredients(List.of(ingredient));

        NutritionInfo nutrition = new NutritionInfo();
        nutrition.setCalories(250.0);
        recipe.setNutrition(nutrition);
        recipe.setMainProcessId(555L);

        RecipeDetailResponseDTO details = service.getRecipeDetails(recipeId, OWNER_ID);

        assertEquals(1, details.getIngredients().size());
        assertEquals(5L, details.getIngredients().get(0).getIngredientId());
        assertEquals(250.0, details.getNutrition().getCalories());
        assertEquals(555L, details.getMainProcessId());
    }

    @Test
    void getRecipeDetails_privateRecipe_nonOwner_isForbidden() {
        Long recipeId = seedRecipe(OWNER_ID, Visibility.PRIVATE);

        assertThrows(RecipeAccessDeniedException.class, () -> service.getRecipeDetails(recipeId, OTHER_USER_ID));
    }

    @Test
    void getRecipeDetails_publicRecipe_nonOwnerCanRead() {
        Long recipeId = seedRecipe(OWNER_ID, Visibility.PUBLIC);

        RecipeDetailResponseDTO details = service.getRecipeDetails(recipeId, OTHER_USER_ID);

        assertEquals(recipeId, details.getId());
    }

    @Test
    void getRecipeDetails_unknownRecipe_notFound() {
        assertThrows(NoSuchElementException.class, () -> service.getRecipeDetails(999L, OWNER_ID));
    }

    @Test
    void updateIngredients_owner_replacesList() {
        Long recipeId = seedRecipe(OWNER_ID, Visibility.PRIVATE);
        RecipeIngredientDTO dto = new RecipeIngredientDTO();
        dto.setIngredientId(9L);
        dto.setQuantity(3.0);
        dto.setUnit(UnitType.GRAM);

        RecipeDetailResponseDTO updated = service.updateIngredients(recipeId, OWNER_ID, List.of(dto));

        assertEquals(1, updated.getIngredients().size());
        assertEquals(9L, updated.getIngredients().get(0).getIngredientId());
    }

    @Test
    void updateIngredients_nonOwner_isRejected() {
        Long recipeId = seedRecipe(OWNER_ID, Visibility.PRIVATE);

        assertThrows(RecipeAccessDeniedException.class, () -> service.updateIngredients(recipeId, OTHER_USER_ID, List.of()));
    }

    @Test
    void updateNutrition_owner_replacesNutrition() {
        Long recipeId = seedRecipe(OWNER_ID, Visibility.PRIVATE);
        NutritionInfoDTO dto = new NutritionInfoDTO();
        dto.setCalories(400.0);
        dto.setServings(4);

        RecipeDetailResponseDTO updated = service.updateNutrition(recipeId, OWNER_ID, dto);

        assertEquals(400.0, updated.getNutrition().getCalories());
        assertEquals(4, updated.getNutrition().getServings());
    }

    @Test
    void updateNutrition_nonOwner_isRejected() {
        Long recipeId = seedRecipe(OWNER_ID, Visibility.PRIVATE);

        assertThrows(RecipeAccessDeniedException.class, () -> service.updateNutrition(recipeId, OTHER_USER_ID, new NutritionInfoDTO()));
    }

    @Test
    void createMainProcess_noExistingMain_createsAndAssigns() {
        Long recipeId = seedRecipe(OWNER_ID, Visibility.PRIVATE);
        ProcessResponseDTO created = ProcessResponseDTO.builder()
                .id(777L).type(ProcessType.MAIN).recipeId(recipeId).name("Recipe " + recipeId).build();
        when(processService.create(eq(recipeId), eq(OWNER_ID), any())).thenReturn(created);

        ProcessResponseDTO result = service.createMainProcess(recipeId, OWNER_ID);

        assertEquals(777L, result.getId());
        assertEquals(777L, recipeStore.get(recipeId).getMainProcessId());
        verify(processService).create(eq(recipeId), eq(OWNER_ID), any());
    }

    @Test
    void createMainProcess_unlinkedMainExists_adoptsItInsteadOfCreating() {
        Long recipeId = seedRecipe(OWNER_ID, Visibility.PRIVATE);
        ProcessResponseDTO orphan = ProcessResponseDTO.builder().id(888L).type(ProcessType.MAIN).recipeId(recipeId).build();
        when(processService.listByRecipe(recipeId, OWNER_ID)).thenReturn(List.of(orphan));
        when(processService.get(recipeId, 888L, OWNER_ID)).thenReturn(orphan);

        ProcessResponseDTO result = service.createMainProcess(recipeId, OWNER_ID);

        assertEquals(888L, result.getId());
        assertEquals(888L, recipeStore.get(recipeId).getMainProcessId());
        verify(processService, never()).create(any(), any(), any());
    }

    @Test
    void createMainProcess_concurrentInsertRejectedByValidator_usesTheOtherMain() {
        Long recipeId = seedRecipe(OWNER_ID, Visibility.PRIVATE);
        ProcessResponseDTO other = ProcessResponseDTO.builder().id(901L).type(ProcessType.MAIN).recipeId(recipeId).build();
        when(processService.listByRecipe(recipeId, OWNER_ID)).thenReturn(List.of()).thenReturn(List.of(other));
        when(processService.create(eq(recipeId), eq(OWNER_ID), any()))
                .thenThrow(new ProcessValidationException("recipe " + recipeId + " already has a MAIN process"));
        when(processService.get(recipeId, 901L, OWNER_ID)).thenReturn(other);

        ProcessResponseDTO result = service.createMainProcess(recipeId, OWNER_ID);

        assertEquals(901L, result.getId());
        assertEquals(901L, recipeStore.get(recipeId).getMainProcessId());
    }

    @Test
    void createMainProcess_concurrentCallLinkedFirst_deletesOwnDuplicate() {
        Long recipeId = seedRecipe(OWNER_ID, Visibility.PRIVATE);
        when(processService.create(eq(recipeId), eq(OWNER_ID), any())).thenAnswer(inv -> {
            // Simulates the other request linking its MAIN while this one is still inserting.
            recipeStore.get(recipeId).setMainProcessId(901L);
            return ProcessResponseDTO.builder().id(902L).type(ProcessType.MAIN).recipeId(recipeId).build();
        });
        ProcessResponseDTO winner = ProcessResponseDTO.builder().id(901L).type(ProcessType.MAIN).recipeId(recipeId).build();
        when(processService.get(recipeId, 901L, OWNER_ID)).thenReturn(winner);

        ProcessResponseDTO result = service.createMainProcess(recipeId, OWNER_ID);

        assertEquals(901L, result.getId());
        assertEquals(901L, recipeStore.get(recipeId).getMainProcessId());
        verify(processService).delete(recipeId, 902L, OWNER_ID);
    }

    @Test
    void createMainProcess_invalidConversion_withNoOtherMain_propagatesError() {
        Long recipeId = seedRecipe(OWNER_ID, Visibility.PRIVATE);
        when(processService.create(eq(recipeId), eq(OWNER_ID), any()))
                .thenThrow(new ProcessValidationException("bad graph"));

        assertThrows(ProcessValidationException.class, () -> service.createMainProcess(recipeId, OWNER_ID));
        assertEquals(null, recipeStore.get(recipeId).getMainProcessId());
    }

    @Test
    void copyToUser_copiesIngredientsNutritionAndProcessesIntoIndependentPrivateRecipe() {
        Long sourceId = seedRecipe(OWNER_ID, Visibility.PUBLIC);
        RecipeTemplate source = recipeStore.get(sourceId);
        RecipeIngredient ingredient = new RecipeIngredient();
        ingredient.setIngredientId(5L);
        ingredient.setQuantity(2.0);
        ingredient.setUnit(UnitType.COUNT);
        ingredient.setNotes("diced");
        source.setIngredients(new ArrayList<>(List.of(ingredient)));
        NutritionInfo nutrition = new NutritionInfo();
        nutrition.setCalories(250.0);
        nutrition.setServings(2);
        source.setNutrition(nutrition);
        source.setMainProcessId(10L);
        when(processService.copyAllToRecipe(eq(sourceId), anyLong()))
                .thenAnswer(inv -> new RecipeProcessCopyResult(Map.of(10L, 20L, 11L, 21L), 20L,
                        Map.of(10L, Map.of("step-1", "step-1-copy"))));

        RecipeTemplateResponseDTO copyDto = service.copyToUser(sourceId, OTHER_USER_ID);

        RecipeTemplate copy = recipeStore.get(copyDto.getId());
        assertNotEquals(sourceId, copy.getId());
        assertEquals(OTHER_USER_ID, copy.getCreatedBy());
        assertEquals(Visibility.PRIVATE, copy.getVisibility());
        assertEquals(sourceId, copy.getCopiedFromRecipeId());
        assertEquals(sourceId, copyDto.getCopiedFromRecipeId());
        assertEquals(20L, copy.getMainProcessId());
        assertEquals(1, copy.getIngredients().size());
        assertEquals(5L, copy.getIngredients().get(0).getIngredientId());
        assertEquals("diced", copy.getIngredients().get(0).getNotes());
        assertNotSame(ingredient, copy.getIngredients().get(0));
        assertEquals(250.0, copy.getNutrition().getCalories());
        assertEquals(2, copy.getNutrition().getServings());
        assertNotSame(nutrition, copy.getNutrition());
        verify(processService).copyAllToRecipe(sourceId, copy.getId());

        // Narration (kept outside the recipe documents) is copied by a listener keyed on the new ids.
        ArgumentCaptor<RecipeCopiedEvent> event = ArgumentCaptor.forClass(RecipeCopiedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(sourceId, event.getValue().sourceRecipeId());
        assertEquals(copy.getId(), event.getValue().targetRecipeId());
        assertEquals(OTHER_USER_ID, event.getValue().userId());
        assertEquals(Map.of(10L, 20L, 11L, 21L), event.getValue().processIdMap());
        assertEquals("step-1-copy", event.getValue().nodeIdMaps().get(10L).get("step-1"));

        // The source recipe is untouched.
        assertEquals(10L, source.getMainProcessId());
        assertEquals(Visibility.PUBLIC, source.getVisibility());
    }

    @Test
    void copyToUser_processCopyFails_removesTheNewRecipe() {
        Long sourceId = seedRecipe(OWNER_ID, Visibility.PUBLIC);
        when(processService.copyAllToRecipe(eq(sourceId), anyLong())).thenThrow(new ProcessValidationException("bad"));

        assertThrows(ProcessValidationException.class, () -> service.copyToUser(sourceId, OTHER_USER_ID));
        assertEquals(1, recipeStore.size(), "only the source recipe remains");
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void copyToUser_privateRecipe_isRejected() {
        Long sourceId = seedRecipe(OWNER_ID, Visibility.PRIVATE);

        assertThrows(RecipeAccessDeniedException.class, () -> service.copyToUser(sourceId, OTHER_USER_ID));
        verify(processService, never()).copyAllToRecipe(any(), any());
    }

    @Test
    void createMainProcess_alreadyExists_isIdempotentAndDoesNotCreateAgain() {
        Long recipeId = seedRecipe(OWNER_ID, Visibility.PRIVATE);
        recipeStore.get(recipeId).setMainProcessId(555L);
        ProcessResponseDTO existing = ProcessResponseDTO.builder().id(555L).type(ProcessType.MAIN).recipeId(recipeId).build();
        when(processService.get(recipeId, 555L, OWNER_ID)).thenReturn(existing);

        ProcessResponseDTO result = service.createMainProcess(recipeId, OWNER_ID);

        assertEquals(555L, result.getId());
        verify(processService, never()).create(any(), any(), any());
    }

    @Test
    void createMainProcess_nonOwner_isRejected() {
        Long recipeId = seedRecipe(OWNER_ID, Visibility.PRIVATE);

        assertThrows(RecipeAccessDeniedException.class, () -> service.createMainProcess(recipeId, OTHER_USER_ID));
    }

    @Test
    void getMainProcess_delegatesToProcessService() {
        Long recipeId = seedRecipe(OWNER_ID, Visibility.PRIVATE);
        recipeStore.get(recipeId).setMainProcessId(555L);
        ProcessResponseDTO existing = ProcessResponseDTO.builder().id(555L).build();
        when(processService.get(recipeId, 555L, OWNER_ID)).thenReturn(existing);

        ProcessResponseDTO result = service.getMainProcess(recipeId, OWNER_ID);

        assertEquals(555L, result.getId());
    }

    @Test
    void getMainProcess_noMainProcessYet_throwsNotFound() {
        Long recipeId = seedRecipe(OWNER_ID, Visibility.PRIVATE);

        assertThrows(NoSuchElementException.class, () -> service.getMainProcess(recipeId, OWNER_ID));
    }

    @Test
    void delete_owner_logsRecipeDeletedWithIdsOnly() {
        Long recipeId = seedRecipe(OWNER_ID, Visibility.PRIVATE);

        try (LogCapture logs = LogCapture.of(RecipeTemplateService.class)) {
            service.delete(recipeId, OWNER_ID);

            List<ILoggingEvent> deleted = logs.events("recipe_deleted");
            assertEquals(1, deleted.size(), logs.allMessages());
            assertEquals(Level.INFO, deleted.get(0).getLevel());
            assertEquals("event=recipe_deleted recipeId=" + recipeId + " userId=" + OWNER_ID,
                    deleted.get(0).getFormattedMessage());
        }
        assertFalse(recipeStore.containsKey(recipeId));
    }

    @Test
    void delete_nonOwner_logsNoDeletion() {
        Long recipeId = seedRecipe(OWNER_ID, Visibility.PRIVATE);

        try (LogCapture logs = LogCapture.of(RecipeTemplateService.class)) {
            assertThrows(RecipeAccessDeniedException.class, () -> service.delete(recipeId, OTHER_USER_ID));

            assertTrue(logs.events("recipe_deleted").isEmpty(), logs.allMessages());
        }
    }

    @Test
    void create_logsRecipeCreatedWithoutTheRecipeText() {
        RecipeTemplateRequestDTO dto = new RecipeTemplateRequestDTO();
        dto.setName("Grandma's secret stew");
        dto.setDescription("Family recipe");
        dto.setCreatedBy(OWNER_ID);

        try (LogCapture logs = LogCapture.of(RecipeTemplateService.class)) {
            RecipeTemplateResponseDTO created = service.create(dto);

            List<ILoggingEvent> events = logs.events("recipe_created");
            assertEquals(1, events.size(), logs.allMessages());
            String message = events.get(0).getFormattedMessage();
            assertTrue(message.contains("recipeId=" + created.getId()), message);
            assertTrue(message.contains("userId=" + OWNER_ID), message);
            assertFalse(logs.allMessages().contains("stew"), "no recipe names in the logs");
            assertFalse(logs.allMessages().contains("Family recipe"), "no recipe descriptions in the logs");
        }
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
}
