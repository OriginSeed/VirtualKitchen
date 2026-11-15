package com.processVisualisation.virtualKitchen.ai.recipe.assetimport;

import com.processVisualisation.virtualKitchen.common.logging.LogAction;

import com.processVisualisation.virtualKitchen.auth.security.CurrentUser;
import com.processVisualisation.virtualKitchen.common.web.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * Copies the images and narration audio a recipe file links to into a recipe created from that file, so the
 * new recipe has its own files. Called once, right after the recipe's steps have been saved. Recipe owner only.
 */
@RestController
@LogAction("recipe")
@RequestMapping("/api/v1/recipes/{recipeId}/assets")
public class RecipeAssetImportController {

    private final RecipeAssetImportService service;

    public RecipeAssetImportController(RecipeAssetImportService service) {
        this.service = service;
    }

    /** Always answers 200 for an owned recipe: entries that could not be copied are listed, with their reason. */
    @PostMapping("/import")
    public ApiResponse<RecipeAssetImportDTO.Response> importAssets(
            @PathVariable Long recipeId, @RequestBody(required = false) RecipeAssetImportDTO.Request request) {
        RecipeAssetImportDTO.Response response = service.importAssets(CurrentUser.requireUserId(), recipeId, request);
        return ApiResponse.<RecipeAssetImportDTO.Response>builder()
                .success(true)
                .message("Recipe assets imported")
                .data(response)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
