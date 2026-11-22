package com.processVisualisation.virtualKitchen.recipe.controller;

import jakarta.validation.constraints.Size;
import com.processVisualisation.virtualKitchen.common.web.InputLimits;
import com.processVisualisation.virtualKitchen.auth.exception.AuthException;
import com.processVisualisation.virtualKitchen.common.web.ApiResponse;
import com.processVisualisation.virtualKitchen.recipe.dto.NutritionInfoDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.ProcessResponseDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeDetailResponseDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeIngredientDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeThumbnailDTO;
import com.processVisualisation.virtualKitchen.recipe.service.RecipeTemplateService;
import com.processVisualisation.virtualKitchen.recipe.service.RecipeThumbnailService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * REST controller for the new Recipe Tool's recipe-level API (built on top
 * of the existing {@code RecipeTemplate} entity): recipe details
 * (ingredients/nutrition/main process id), and ingredient/nutrition/main
 * process management. Coexists with the pre-existing
 * {@code /api/v1/process-templates} endpoints (base recipe CRUD, visibility,
 * copy) — see {@link RecipeTemplateController}; this controller only adds
 * the fields/actions specific to the new Process model.
 */
@RestController
@RequestMapping("/api/v1/recipes/{recipeId}")
public class RecipeDetailController {

    @Autowired
    private RecipeTemplateService recipeService;

    @Autowired
    private RecipeThumbnailService thumbnailService;

    /**
     * Retrieves a recipe's detail view (ingredients, nutrition, main process id, plus base
     * fields). Follows the recipe's existing visibility: owner can always read it; a non-owner
     * can read it only if it is public.
     *
     * @param recipeId the id of the recipe to fetch
     * @return an ApiResponse wrapping the recipe's detail view
     */
    @GetMapping
    public ApiResponse<RecipeDetailResponseDTO> get(@PathVariable Long recipeId) {
        return build(recipeService.getRecipeDetails(recipeId, optionalCurrentUserId()), "fetched");
    }

    /**
     * Retrieves the image that represents a recipe: its most recently generated step visual, or
     * the default recipe icon when it has none. Follows the same visibility rule as {@link #get}.
     * Never generates an image.
     *
     * @param recipeId the id of the recipe whose thumbnail to resolve
     * @return an ApiResponse wrapping the resolved thumbnail
     */
    @GetMapping("/thumbnail")
    public ApiResponse<RecipeThumbnailDTO> getThumbnail(@PathVariable Long recipeId) {
        return build(thumbnailService.resolve(recipeId, optionalCurrentUserId()), "fetched");
    }

    /**
     * Replaces a recipe's ingredient list. Requires the caller to own the recipe.
     *
     * @param recipeId    the id of the recipe to update
     * @param ingredients the replacement ingredient list
     * @return an ApiResponse wrapping the updated recipe's detail view
     */
    @PutMapping("/ingredients")
    public ApiResponse<RecipeDetailResponseDTO> updateIngredients(
            @PathVariable Long recipeId,
            @Valid @RequestBody @Size(max = InputLimits.RECIPE_INGREDIENTS, message = "Too many ingredients (at most " + InputLimits.RECIPE_INGREDIENTS + ")") List<RecipeIngredientDTO> ingredients
    ) {
        return build(recipeService.updateIngredients(recipeId, currentUserId(), ingredients), "updated");
    }

    /**
     * Replaces a recipe's nutrition info. Requires the caller to own the recipe.
     *
     * @param recipeId  the id of the recipe to update
     * @param nutrition the replacement nutrition info
     * @return an ApiResponse wrapping the updated recipe's detail view
     */
    @PutMapping("/nutrition")
    public ApiResponse<RecipeDetailResponseDTO> updateNutrition(
            @PathVariable Long recipeId,
            @RequestBody NutritionInfoDTO nutrition
    ) {
        return build(recipeService.updateNutrition(recipeId, currentUserId(), nutrition), "updated");
    }

    /**
     * Retrieves a recipe's MAIN process. Follows the same visibility rule as {@link #get}.
     *
     * @param recipeId the id of the recipe whose main process to fetch
     * @return an ApiResponse wrapping the recipe's MAIN process
     */
    @GetMapping("/main-process")
    public ApiResponse<ProcessResponseDTO> getMainProcess(@PathVariable Long recipeId) {
        return build(recipeService.getMainProcess(recipeId, optionalCurrentUserId()), "fetched");
    }

    /**
     * Ensures a recipe has a MAIN process, creating one if it doesn't already have one
     * (idempotent). Requires the caller to own the recipe.
     *
     * @param recipeId the id of the recipe to create/assign a main process for
     * @return an ApiResponse wrapping the recipe's (possibly newly created) MAIN process
     */
    @PostMapping("/main-process")
    public ApiResponse<ProcessResponseDTO> createMainProcess(@PathVariable Long recipeId) {
        return build(recipeService.createMainProcess(recipeId, currentUserId()), "created");
    }

    private Long currentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                : null;
        if (!(principal instanceof Long userId)) {
            throw new AuthException("Authentication required to modify this recipe", HttpStatus.UNAUTHORIZED);
        }
        return userId;
    }

    private Long optionalCurrentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                : null;
        return principal instanceof Long userId ? userId : null;
    }

    private <T> ApiResponse<T> build(T data, String msg) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(msg)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
