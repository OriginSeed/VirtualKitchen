package com.processVisualisation.virtualKitchen.recipe.service;

import com.processVisualisation.virtualKitchen.common.persistence.SequenceGeneratorService;
import com.processVisualisation.virtualKitchen.recipe.dto.NutritionInfoDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.ProcessRequestDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.ProcessResponseDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeDetailResponseDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeIngredientDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeTemplateRequestDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeTemplateResponseDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeTemplateUpdateDTO;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Persists recipe templates via
 * RecipeTemplateRepository, enforces ownership (via requireOwner) on mutating operations,
 * and, when a public template is copied to a user library via copyToUser, deep-clones the
 * template's ingredients, nutrition and Process documents onto the new template so the copy is fully independent of the original. Maps between entities and
 * DTOs via RecipeTemplateMapper.
 */
@Service
public class RecipeTemplateService {

    private static final Logger log = LoggerFactory.getLogger(RecipeTemplateService.class);

    @Autowired
    private RecipeTemplateRepository repo;

    @Autowired
    private RecipeTemplateMapper mapper;

    @Autowired
    private SequenceGeneratorService seq;

    @Autowired
    private ProcessService processService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * One lock per recipe for {@link #createMainProcess}: concurrent calls for the same recipe (a page opened twice
     * at once) run one after another, so the second finds the first's MAIN process instead of briefly inserting a
     * duplicate that a reader could load before it is cleaned up.
     */
    private final ConcurrentHashMap<Long, Object> mainProcessLocks = new ConcurrentHashMap<>();

    /**
     * Creates and persists a new recipe template, assigning it a new sequence-generated id.
     *
     * @param dto the template details to persist
     * @return the created template
     */
    public RecipeTemplateResponseDTO create(RecipeTemplateRequestDTO dto){
        RecipeTemplate pt = mapper.toEntity(dto);
        pt.setId(seq.generateSequence(RecipeTemplate.SEQUENCE_NAME));
        RecipeTemplate saved = repo.save(pt);
        log.info("event=recipe_created recipeId={} userId={} visibility={} ingredientCount={}",
                saved.getId(), saved.getCreatedBy(), saved.getVisibility(), sizeOf(saved.getIngredients()));
        return mapper.toDTO(saved);
    }

    /**
     * Retrieves a single recipe template by id.
     *
     * @param id the id of the template to fetch
     * @param requestingUserId the caller (from the verified JWT)
     * @return the matching template
     * @throws java.util.NoSuchElementException if no template exists with the given id
     * @throws RecipeAccessDeniedException if the template is private and not the caller's
     */
    public RecipeTemplateResponseDTO get(Long id, Long requestingUserId){
        // Same rule as the detail view: the owner always, anyone else only while it is public.
        return mapper.toDTO(requireAccessible(id, requestingUserId));
    }

    /**
     * Retrieves all recipe templates owned by a given user.
     *
     * @param userId the id of the owning user to filter by
     * @return the templates created by that user
     */
    public List<RecipeTemplateResponseDTO> getByUser(Long userId){
        return repo.findByCreatedBy(userId)
                .stream()
                .map(mapper::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Retrieves all publicly visible recipe templates that were not created by the given user.
     *
     * @param userId the id of the user to exclude as owner
     * @return the matching public templates owned by other users
     */
    public List<RecipeTemplateResponseDTO> getGlobalRecipes(Long userId){
        return repo.findByVisibilityAndCreatedByNot(Visibility.PUBLIC, userId)
                .stream()
                .map(mapper::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Updates the name and description of a recipe template, after verifying the requesting
     * user owns it.
     *
     * @param id     the id of the template to update
     * @param userId the id of the user requesting the update, used for ownership verification
     * @param dto    the new name/description values
     * @return the updated template
     * @throws java.util.NoSuchElementException if no template exists with the given id
     * @throws RecipeAccessDeniedException if userId does not own the template
     */
    public RecipeTemplateResponseDTO update(Long id, Long userId, RecipeTemplateUpdateDTO dto){
        RecipeTemplate pt = repo.findById(id).orElseThrow();
        requireOwner(pt, userId);
        pt.setName(dto.getName());
        pt.setDescription(dto.getDescription());
        RecipeTemplate saved = repo.save(pt);
        log.info("event=recipe_updated recipeId={} userId={}", saved.getId(), userId);
        return mapper.toDTO(saved);
    }

    /**
     * Sets a recipe's default preparation guidance, after verifying the requesting user owns it. Blank text
     * goes back to the application's default.
     *
     * @param id     the id of the recipe
     * @param userId the id of the user requesting the change, used for ownership verification
     * @param notes  the new guidance; null or blank for the default
     * @return the updated recipe detail
     * @throws java.util.NoSuchElementException if no template exists with the given id
     * @throws RecipeAccessDeniedException if userId does not own the template
     */
    public RecipeDetailResponseDTO updatePreparationNotes(Long id, Long userId, String notes) {
        RecipeTemplate pt = repo.findById(id).orElseThrow();
        requireOwner(pt, userId);
        pt.setPreparationNotes(notes == null || notes.isBlank() ? null : notes.strip());
        RecipeTemplate saved = repo.save(pt);
        log.info("event=recipe_preparation_notes_updated recipeId={} userId={} reset={} length={}",
                saved.getId(), userId, saved.getPreparationNotes() == null,
                saved.getPreparationNotes() == null ? 0 : saved.getPreparationNotes().length());
        return mapper.toDetailDTO(saved);
    }

    /**
     * Deletes a recipe template, after verifying the requesting user owns it.
     *
     * @param id     the id of the template to delete
     * @param userId the id of the user requesting the deletion, used for ownership verification
     * @throws java.util.NoSuchElementException if no template exists with the given id
     * @throws RecipeAccessDeniedException if userId does not own the template
     */
    public void delete(Long id, Long userId){
        RecipeTemplate pt = repo.findById(id).orElseThrow();
        requireOwner(pt, userId);
        repo.deleteById(id);
        log.info("event=recipe_deleted recipeId={} userId={}", id, userId);
    }

    /**
     * Updates the visibility (e.g. private/public) of a recipe template, after verifying the
     * requesting user owns it.
     *
     * @param id         the id of the template to update
     * @param userId     the id of the user requesting the update, used for ownership verification
     * @param visibility the new visibility value
     * @return the updated template
     * @throws java.util.NoSuchElementException if no template exists with the given id
     * @throws RecipeAccessDeniedException if userId does not own the template
     */
    public RecipeTemplateResponseDTO updateVisibility(Long id, Long userId, Visibility visibility){
        RecipeTemplate pt = repo.findById(id).orElseThrow();
        requireOwner(pt, userId);
        Visibility previous = pt.getVisibility();
        pt.setVisibility(visibility);
        RecipeTemplate saved = repo.save(pt);
        log.info("event=recipe_visibility_changed recipeId={} userId={} fromVisibility={} visibility={}",
                saved.getId(), userId, previous, saved.getVisibility());
        return mapper.toDTO(saved);
    }

    /**
     * Copies a publicly visible recipe template into a new private template owned by the given
     * user, as a complete, independent copy: its ingredients and nutrition, every Process
     * document (MAIN and every SUBPROCESS, with process ids and Action On subprocess references
     * remapped to the copies — see {@link ProcessService#copyAllToRecipe}) with the copy's
     * mainProcessId pointing at the copied MAIN.
     *
     * @param id     the id of the public template to copy
     * @param userId the id of the user the copy will be owned by
     * @return the newly created copy
     * @throws java.util.NoSuchElementException if no template exists with the given id
     * @throws RecipeAccessDeniedException if the source template is not Visibility.PUBLIC
     */
    public RecipeTemplateResponseDTO copyToUser(Long id, Long userId){
        RecipeTemplate original = repo.findById(id).orElseThrow();

        if (original.getVisibility() != Visibility.PUBLIC) {
            throw new RecipeAccessDeniedException("Only public recipes can be added to My Recipes");
        }

        RecipeTemplate copy = new RecipeTemplate();
        copy.setId(seq.generateSequence(RecipeTemplate.SEQUENCE_NAME));
        copy.setName(original.getName());
        copy.setDescription(original.getDescription());
        copy.setCreatedBy(userId);
        copy.setVisibility(Visibility.PRIVATE);
        copy.setCopiedFromRecipeId(original.getId());
        copy.setIngredients(cloneIngredients(original.getIngredients()));
        copy.setNutrition(cloneNutrition(original.getNutrition()));
        copy.setPreparationNotes(original.getPreparationNotes());

        // Saved before its processes are copied: ProcessValidator requires each process's recipe to exist.
        RecipeTemplate saved = repo.save(copy);

        RecipeProcessCopyResult processCopy;
        try {
            processCopy = processService.copyAllToRecipe(original.getId(), saved.getId());
        } catch (RuntimeException e) {
            // copyAllToRecipe validates every process before saving any, so nothing but the
            // template itself has been persisted at this point.
            repo.deleteById(saved.getId());
            throw e;
        }

        if (processCopy.mainProcessId() != null) {
            saved.setMainProcessId(processCopy.mainProcessId());
            saved = repo.save(saved);
        }

        // Step narration lives beside the recipe, keyed by recipe and step id; its listener copies it
        // onto the new ids so the copy plays without regenerating (and paying for) the audio.
        eventPublisher.publishEvent(new RecipeCopiedEvent(original.getId(), saved.getId(), userId,
                processCopy.processIdMap(), processCopy.nodeIdMaps()));

        log.info("event=recipe_copied sourceRecipeId={} recipeId={} userId={} processCount={} ingredientCount={}",
                original.getId(), saved.getId(), userId, sizeOf(processCopy.processIdMap()), sizeOf(saved.getIngredients()));
        return mapper.toDTO(saved);
    }

    /**
     * {@inheritDoc}
     */
    public RecipeDetailResponseDTO getRecipeDetails(Long recipeId, Long requestingUserId) {
        RecipeTemplate recipe = requireAccessible(recipeId, requestingUserId);
        return mapper.toDetailDTO(recipe);
    }

    /**
     * {@inheritDoc}
     */
    public RecipeDetailResponseDTO updateIngredients(Long recipeId, Long userId, List<RecipeIngredientDTO> ingredients) {
        RecipeTemplate recipe = repo.findById(recipeId)
                .orElseThrow(() -> new NoSuchElementException("Recipe not found: " + recipeId));
        requireOwner(recipe, userId);

        recipe.setIngredients(mapper.toIngredientEntities(ingredients));
        RecipeTemplate saved = repo.save(recipe);
        log.info("event=recipe_ingredients_updated recipeId={} userId={} ingredientCount={}",
                saved.getId(), userId, sizeOf(saved.getIngredients()));
        return mapper.toDetailDTO(saved);
    }

    /**
     * {@inheritDoc}
     */
    public RecipeDetailResponseDTO updateNutrition(Long recipeId, Long userId, NutritionInfoDTO nutrition) {
        RecipeTemplate recipe = repo.findById(recipeId)
                .orElseThrow(() -> new NoSuchElementException("Recipe not found: " + recipeId));
        requireOwner(recipe, userId);

        recipe.setNutrition(mapper.toNutritionEntity(nutrition));
        RecipeTemplate saved = repo.save(recipe);
        log.info("event=recipe_nutrition_updated recipeId={} userId={} cleared={}",
                saved.getId(), userId, saved.getNutrition() == null);
        return mapper.toDetailDTO(saved);
    }

    /**
     * {@inheritDoc}
     */
    public ProcessResponseDTO getMainProcess(Long recipeId, Long requestingUserId) {
        RecipeTemplate recipe = requireAccessible(recipeId, requestingUserId);
        if (recipe.getMainProcessId() == null) {
            throw new NoSuchElementException("Recipe " + recipeId + " does not have a main process yet");
        }
        return processService.get(recipeId, recipe.getMainProcessId(), requestingUserId);
    }

    /**
     * {@inheritDoc}
     */
    public ProcessResponseDTO createMainProcess(Long recipeId, Long userId) {
        synchronized (mainProcessLocks.computeIfAbsent(recipeId, id -> new Object())) {
            return createMainProcessOnce(recipeId, userId);
        }
    }

    /** The body of {@link #createMainProcess}; its race handling still covers calls from other instances. */
    private ProcessResponseDTO createMainProcessOnce(Long recipeId, Long userId) {
        RecipeTemplate recipe = repo.findById(recipeId)
                .orElseThrow(() -> new NoSuchElementException("Recipe not found: " + recipeId));
        requireOwner(recipe, userId);

        if (recipe.getMainProcessId() != null) {
            // Idempotent: a main process already exists, so repeated calls never create a second one.
            return processService.get(recipeId, recipe.getMainProcessId(), userId);
        }

        // A MAIN process can exist without being linked yet (an earlier call that was interrupted,
        // or a concurrent one still in flight) — adopt it rather than creating a second one.
        Optional<ProcessResponseDTO> unlinkedMain = findMainProcess(recipeId, userId);
        if (unlinkedMain.isPresent()) {
            return processService.get(recipeId, linkMainProcess(recipeId, unlinkedMain.get().getId()), userId);
        }

        ProcessRequestDTO dto = new ProcessRequestDTO();
        dto.setType(ProcessType.MAIN);
        dto.setName(recipe.getName());
        dto.setDescription(recipe.getDescription());

        ProcessResponseDTO created;
        try {
            created = processService.create(recipeId, userId, dto);
        } catch (ProcessValidationException e) {
            // Lost a race: a concurrent call created the MAIN process between the check above and
            // this insert, so the validator rejected a second one. Use that one instead.
            Optional<ProcessResponseDTO> concurrentMain = findMainProcess(recipeId, userId);
            if (concurrentMain.isEmpty()) {
                throw e;
            }
            return processService.get(recipeId, linkMainProcess(recipeId, concurrentMain.get().getId()), userId);
        }

        Long linkedId = linkMainProcess(recipeId, created.getId());
        if (!created.getId().equals(linkedId)) {
            // Both concurrent calls inserted a MAIN process; the other one was linked first, so
            // this one is an unreferenced duplicate.
            processService.delete(recipeId, created.getId(), userId);
            return processService.get(recipeId, linkedId, userId);
        }
        return created;
    }

    private Optional<ProcessResponseDTO> findMainProcess(Long recipeId, Long userId) {
        return processService.listByRecipe(recipeId, userId)
                .stream()
                .filter(process -> process.getType() == ProcessType.MAIN)
                .findFirst();
    }

    /**
     * Links {@code processId} as the recipe's main process unless it already has one, and returns
     * whichever process id the recipe actually ends up linked to.
     */
    private Long linkMainProcess(Long recipeId, Long processId) {
        repo.linkMainProcessIfUnset(recipeId, processId);
        return repo.findById(recipeId)
                .map(RecipeTemplate::getMainProcessId)
                .orElseThrow(() -> new NoSuchElementException("Recipe not found: " + recipeId));
    }

    /**
     * Verifies that a recipe is accessible for reading by the given user: the owner can always
     * read it; a non-owner (or unauthenticated caller) can read it only if it is
     * {@link Visibility#PUBLIC}.
     *
     * @param recipeId         the id of the recipe to fetch
     * @param requestingUserId the id of the requesting user, or null if unauthenticated
     * @return the recipe, if accessible
     * @throws NoSuchElementException      if no recipe exists with the given id
     * @throws RecipeAccessDeniedException if the recipe is private and requestingUserId is not its owner
     */
    private RecipeTemplate requireAccessible(Long recipeId, Long requestingUserId) {
        RecipeTemplate recipe = repo.findById(recipeId)
                .orElseThrow(() -> new NoSuchElementException("Recipe not found: " + recipeId));
        if (recipe.getVisibility() == Visibility.PUBLIC) {
            return recipe;
        }
        if (requestingUserId != null && requestingUserId.equals(recipe.getCreatedBy())) {
            return recipe;
        }
        throw new RecipeAccessDeniedException("You do not have permission to view this recipe");
    }

    /**
     * Verifies that userId is the owner of the given recipe template.
     *
     * @param pt     the template to check ownership of
     * @param userId the id of the user attempting the operation
     * @throws RecipeAccessDeniedException if userId is null or does not match the template owner
     */
    private void requireOwner(RecipeTemplate pt, Long userId){
        if (userId == null || !userId.equals(pt.getCreatedBy())) {
            throw new RecipeAccessDeniedException("You do not have permission to modify this recipe");
        }
    }

    private static int sizeOf(java.util.Collection<?> items) {
        return items == null ? 0 : items.size();
    }

    private static int sizeOf(java.util.Map<?, ?> items) {
        return items == null ? 0 : items.size();
    }

    private List<RecipeIngredient> cloneIngredients(List<RecipeIngredient> ingredients) {
        List<RecipeIngredient> result = new ArrayList<>();
        if (ingredients == null) {
            return result;
        }

        for (RecipeIngredient ingredient : ingredients) {
            RecipeIngredient copy = new RecipeIngredient();
            copy.setIngredientId(ingredient.getIngredientId());
            copy.setQuantity(ingredient.getQuantity());
            copy.setUnit(ingredient.getUnit());
            copy.setNotes(ingredient.getNotes());
            copy.setPreparation(ingredient.getPreparation());
            result.add(copy);
        }

        return result;
    }

    private NutritionInfo cloneNutrition(NutritionInfo nutrition) {
        if (nutrition == null) {
            return null;
        }

        NutritionInfo copy = new NutritionInfo();
        copy.setCalories(nutrition.getCalories());
        copy.setProteinGrams(nutrition.getProteinGrams());
        copy.setCarbohydratesGrams(nutrition.getCarbohydratesGrams());
        copy.setFatGrams(nutrition.getFatGrams());
        copy.setFiberGrams(nutrition.getFiberGrams());
        copy.setSodiumMilligrams(nutrition.getSodiumMilligrams());
        copy.setServings(nutrition.getServings());
        return copy;
    }
}
