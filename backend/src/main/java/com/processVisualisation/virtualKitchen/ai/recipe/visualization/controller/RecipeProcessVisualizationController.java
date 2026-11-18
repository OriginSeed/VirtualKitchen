package com.processVisualisation.virtualKitchen.ai.recipe.visualization.controller;

import com.processVisualisation.virtualKitchen.common.logging.LogAction;

import com.processVisualisation.virtualKitchen.ai.platform.preference.RunModelChoices;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.StoryboardProperties;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.LegacyVisualsDisabledException;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationJobService;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.dto.VisualizationJobRequestDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.dto.VisualizationJobResponseDTO;
import com.processVisualisation.virtualKitchen.auth.exception.AuthException;
import com.processVisualisation.virtualKitchen.common.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * REST controller for AI-driven visualization of one Process (MAIN or SUBPROCESS)'s own STEP
 * nodes, as an asynchronous background job that can be polled for progress. Delegates
 * to {@link RecipeProcessVisualizationJobService}.
 */
@RestController
@LogAction("visualization")
@RequestMapping("/api/v1/recipes/{recipeId}/processes/{processId}/visualization")
public class RecipeProcessVisualizationController {

    private final RecipeProcessVisualizationJobService recipeProcessVisualizationJobService;
    private final RunModelChoices runModelChoices;

    private final StoryboardProperties storyboardProperties;

    public RecipeProcessVisualizationController(RecipeProcessVisualizationJobService recipeProcessVisualizationJobService,
                                                RunModelChoices runModelChoices, StoryboardProperties storyboardProperties) {
        this.recipeProcessVisualizationJobService = recipeProcessVisualizationJobService;
        this.runModelChoices = runModelChoices;
        this.storyboardProperties = storyboardProperties;
    }

    /**
     * Starts an async visualization job for this process's own steps and returns immediately —
     * the actual generation work runs on a background task pool. Poll {@link #getJobStatus} with
     * the returned jobId for progress/completion. If a visualization is already running for this
     * process, that job is returned (with {@code reused=true}) instead of starting a second one.
     * <p>
     * The optional body names the models picked for this run (text for the image prompts, image for the pictures);
     * without one, the user's account setting applies. {@code overwriteExisting} regenerates and replaces images the
     * steps already have; by default they are kept and only missing images are drawn.
     */
    @PostMapping("/jobs")
    public ApiResponse<VisualizationJobResponseDTO> startJob(@PathVariable Long recipeId, @PathVariable Long processId,
                                                             @RequestBody(required = false) VisualizationJobRequestDTO request) {
        Long userId = currentUserId();
        if (!storyboardProperties.isLegacyStepVisualsEnabled()) {
            throw new LegacyVisualsDisabledException();
        }
        boolean overwriteExisting = request != null && request.isOverwriteExisting();
        VisualizationJobResponseDTO data = runModelChoices.startWith(request != null ? request.getModelKeys() : null,
                () -> recipeProcessVisualizationJobService.startJob(userId, recipeId, processId, overwriteExisting));
        return build(data, "Process visualization job started");
    }

    /**
     * Retrieves the current status (and, once complete, per-step results) of a previously started
     * process visualization job.
     */
    @GetMapping("/jobs/{jobId}")
    public ApiResponse<VisualizationJobResponseDTO> getJobStatus(
            @PathVariable Long recipeId, @PathVariable Long processId, @PathVariable String jobId) {
        VisualizationJobResponseDTO data = recipeProcessVisualizationJobService.getJobStatus(currentUserId(), recipeId, processId, jobId);
        return build(data, "Process visualization job status");
    }

    private Long currentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                : null;
        if (!(principal instanceof Long userId)) {
            throw new AuthException("Authentication required to generate a visualization", HttpStatus.UNAUTHORIZED);
        }
        return userId;
    }

    private <T> ApiResponse<T> build(T data, String message) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
