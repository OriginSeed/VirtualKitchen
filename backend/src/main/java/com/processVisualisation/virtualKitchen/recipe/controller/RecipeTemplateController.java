package com.processVisualisation.virtualKitchen.recipe.controller;

import com.processVisualisation.virtualKitchen.common.logging.LogAction;

import com.processVisualisation.virtualKitchen.auth.exception.AuthException;
import com.processVisualisation.virtualKitchen.auth.security.CurrentUser;
import com.processVisualisation.virtualKitchen.common.web.ApiResponse;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeDetailResponseDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipePreparationNotesUpdateDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeTemplateRequestDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeTemplateResponseDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeTemplateUpdateDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeThumbnailDTO;
import com.processVisualisation.virtualKitchen.recipe.model.Visibility;
import com.processVisualisation.virtualKitchen.recipe.service.RecipeTemplateService;
import com.processVisualisation.virtualKitchen.recipe.service.RecipeThumbnailService;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * REST controller for creating, reading, updating, deleting, and sharing recipe/process
 * templates. Recipe templates are user-owned. The caller is always the user in the verified JWT
 * ({@link CurrentUser}) — never a {@code userId} sent by the client, which older clients may still
 * add as a query parameter and which is ignored. The service layer verifies ownership before any
 * change and rejects the request otherwise (403).
 */
@RestController
@LogAction("recipe")
@RequestMapping("/api/v1/process-templates")
public class RecipeTemplateController {

    @Autowired
    private RecipeTemplateService service;

    @Autowired
    private RecipeThumbnailService thumbnailService;

    /**
     * Creates a new recipe template.
     *
     * @param dto the template details to persist (name, description, owner, etc.)
     * @return an ApiResponse wrapping the created template
     */
    @PostMapping
    public ApiResponse<RecipeTemplateResponseDTO> create(@RequestBody RecipeTemplateRequestDTO dto){
        // The new recipe belongs to the caller, whatever the body says.
        dto.setCreatedBy(CurrentUser.requireUserId());
        return build(service.create(dto), "created");
    }

    /**
     * Retrieves a single recipe template by id.
     *
     * @param id the id of the template to fetch
     * @return an ApiResponse wrapping the matching template
     * @throws java.util.NoSuchElementException if no template exists with the given id
     * @throws com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException if it is private and not the caller's
     */
    @GetMapping("/{id}")
    public ApiResponse<RecipeTemplateResponseDTO> get(@PathVariable Long id){
        return build(service.get(id, CurrentUser.requireUserId()), "fetched");
    }

    /**
     * Retrieves all recipe templates owned by a given user.
     *
     * @param userId the id of the owning user to filter by; must be the caller
     * @return an ApiResponse wrapping the templates created by that user
     * @throws AuthException 403 when asking for someone else's recipes
     */
    @GetMapping("/user/{userId}")
    public ApiResponse<List<RecipeTemplateResponseDTO>> getByUser(@PathVariable Long userId){
        return build(withThumbnails(service.getByUser(requireSelf(userId))), "fetched");
    }

    /**
     * Retrieves all publicly visible recipe templates that were not created by the given user,
     * i.e. the recipes available for that user to browse/copy from other authors.
     *
     * @param userId the id of the user to exclude as owner; must be the caller
     * @return an ApiResponse wrapping the matching public templates owned by other users
     */
    @GetMapping("/global/{userId}")
    public ApiResponse<List<RecipeTemplateResponseDTO>> getGlobalRecipes(@PathVariable Long userId){
        return build(withThumbnails(service.getGlobalRecipes(requireSelf(userId))), "fetched");
    }

    /** The caller's id, when the path names the caller; 403 when it names anyone else. */
    private static Long requireSelf(Long pathUserId) {
        Long userId = CurrentUser.requireUserId();
        if (!userId.equals(pathUserId)) {
            throw new AuthException("You can only list your own recipes", HttpStatus.FORBIDDEN);
        }
        return userId;
    }

    /** Fills each listed recipe's thumbnail (latest generated visual, or the default icon) in one batch. */
    private List<RecipeTemplateResponseDTO> withThumbnails(List<RecipeTemplateResponseDTO> recipes) {
        Map<Long, RecipeThumbnailDTO> thumbnails = thumbnailService.resolveAll(
                recipes.stream().map(RecipeTemplateResponseDTO::getId).filter(Objects::nonNull).toList());
        recipes.forEach(recipe -> {
            RecipeThumbnailDTO thumbnail = thumbnails.get(recipe.getId());
            if (thumbnail != null) {
                recipe.setThumbnailUrl(thumbnail.getThumbnailUrl());
                recipe.setFallbackIcon(thumbnail.getFallbackIcon());
            }
        });
        return recipes;
    }

    /**
     * Updates the name and description of a recipe template. The caller must own the template;
     * ownership is verified in the service layer against the authenticated caller.
     *
     * @param id     the id of the template to update
     * @param dto    the new name/description values
     * @return an ApiResponse wrapping the updated template
     * @throws java.util.NoSuchElementException if no template exists with the given id
     * @throws com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException if the caller does not own the template
     */
    @PutMapping("/{id}")
    public ApiResponse<RecipeTemplateResponseDTO> update(@PathVariable Long id,
                                                         @RequestBody RecipeTemplateUpdateDTO dto){
        return build(service.update(id, CurrentUser.requireUserId(), dto), "updated");
    }

    /**
     * Updates the visibility (e.g. private/public) of a recipe template. The caller must own
     * the template; ownership is verified in the service layer against the authenticated caller.
     *
     * @param id         the id of the template to update
     * @param visibility the new visibility value
     * @return an ApiResponse wrapping the updated template
     * @throws java.util.NoSuchElementException if no template exists with the given id
     * @throws com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException if the caller does not own the template
     */
    @PutMapping("/{id}/visibility")
    public ApiResponse<RecipeTemplateResponseDTO> updateVisibility(@PathVariable Long id,
                                                                   @RequestParam Visibility visibility){
        return build(service.updateVisibility(id, CurrentUser.requireUserId(), visibility), "updated");
    }

    /**
     * Sets the recipe's default preparation guidance (blank resets it to the application's default). The caller
     * must own the template; ownership is verified in the service layer against the authenticated caller.
     *
     * @param id     the id of the template to update
     * @param dto    the new guidance
     * @return an ApiResponse wrapping the updated recipe detail
     */
    @PutMapping("/{id}/preparation-notes")
    public ApiResponse<RecipeDetailResponseDTO> updatePreparationNotes(@PathVariable Long id,
                                                                       @Valid @RequestBody RecipePreparationNotesUpdateDTO dto){
        return build(service.updatePreparationNotes(id, CurrentUser.requireUserId(), dto.getPreparationNotes()), "updated");
    }

    /**
     * Copies a publicly visible recipe template (and its saved flow graph) into a new private
     * template owned by the caller.
     *
     * @param id     the id of the public template to copy
     * @return an ApiResponse wrapping the newly created copy
     * @throws java.util.NoSuchElementException if no template exists with the given id
     * @throws com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException if the source template is not public
     */
    @PostMapping("/{id}/copy")
    public ApiResponse<RecipeTemplateResponseDTO> copy(@PathVariable Long id){
        return build(service.copyToUser(id, CurrentUser.requireUserId()), "created");
    }

    /**
     * Deletes a recipe template. The caller must own the template; ownership is verified in the
     * service layer against the authenticated caller.
     *
     * @param id     the id of the template to delete
     * @return an ApiResponse with no data, confirming deletion
     * @throws java.util.NoSuchElementException if no template exists with the given id
     * @throws com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException if the caller does not own the template
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id){
        service.delete(id, CurrentUser.requireUserId());
        return build(null, "deleted");
    }

    /**
     * Wraps a payload and status message into a standard success ApiResponse, stamped with the
     * current timestamp.
     *
     * @param data the response payload to wrap
     * @param msg  the status message describing the operation performed
     * @param <T>  the type of the response payload
     * @return the wrapped ApiResponse
     */
    private <T> ApiResponse<T> build(T data, String msg){
        return ApiResponse.<T>builder()
                .success(true)
                .message(msg)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
