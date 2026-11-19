package com.processVisualisation.virtualKitchen.ai.recipe.workflow.controller;

import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessGenerationJobService;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.RecipeStoryboardJobService;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationJobService;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.RecipeAiWorkflowService;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto.RecipeActiveJobsResponseDTO;
import com.processVisualisation.virtualKitchen.auth.exception.AuthException;
import com.processVisualisation.virtualKitchen.common.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * REST controller listing the caller's background AI jobs for one recipe, so the frontend can
 * re-attach to them (show progress, disable the start buttons) after a reload or after navigating
 * away and back — the jobIds it held in memory are gone by then.
 */
@RestController
@RequestMapping("/api/v1/recipes/{recipeId}/jobs")
public class RecipeJobsController {

    private final RecipeProcessGenerationJobService recipeProcessGenerationJobService;
    private final RecipeProcessVisualizationJobService recipeProcessVisualizationJobService;
    private final RecipeAiWorkflowService recipeAiWorkflowService;
    private final RecipeStoryboardJobService recipeStoryboardJobService;

    public RecipeJobsController(
            RecipeProcessGenerationJobService recipeProcessGenerationJobService,
            RecipeProcessVisualizationJobService recipeProcessVisualizationJobService,
            RecipeAiWorkflowService recipeAiWorkflowService,
            RecipeStoryboardJobService recipeStoryboardJobService) {
        this.recipeProcessGenerationJobService = recipeProcessGenerationJobService;
        this.recipeProcessVisualizationJobService = recipeProcessVisualizationJobService;
        this.recipeAiWorkflowService = recipeAiWorkflowService;
        this.recipeStoryboardJobService = recipeStoryboardJobService;
    }

    /**
     * Returns the running (or completed-but-unapplied) generation job, every running
     * visualization job, the AI Recipe Creation workflow and the running storyboard generation the caller has
     * for this recipe.
     */
    @GetMapping("/active")
    public ApiResponse<RecipeActiveJobsResponseDTO> getActiveJobs(@PathVariable Long recipeId) {
        Long userId = currentUserId();
        RecipeActiveJobsResponseDTO data = RecipeActiveJobsResponseDTO.builder()
                .generation(recipeProcessGenerationJobService.findResumable(userId, recipeId).orElse(null))
                .visualizations(recipeProcessVisualizationJobService.findActive(userId, recipeId))
                .workflow(recipeAiWorkflowService.findCurrent(userId, recipeId).orElse(null))
                .storyboard(recipeStoryboardJobService.findActive(userId, recipeId).orElse(null))
                .build();
        return ApiResponse.<RecipeActiveJobsResponseDTO>builder()
                .success(true)
                .message("Active recipe jobs")
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    private Long currentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                : null;
        if (!(principal instanceof Long userId)) {
            throw new AuthException("Authentication required to view recipe jobs", HttpStatus.UNAUTHORIZED);
        }
        return userId;
    }
}
