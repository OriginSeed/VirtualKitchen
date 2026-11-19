package com.processVisualisation.virtualKitchen.ai.recipe.storyboard.controller;

import com.processVisualisation.virtualKitchen.ai.platform.preference.RunModelChoices;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.RecipeStoryboardJobService;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.StoryboardMode;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.StoryboardStepFixService;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.dto.StoryboardStepFixRequestDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.dto.StoryboardStepFixResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.dto.RecipeStoryboardResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.dto.StoryboardJobRequestDTO;
import com.processVisualisation.virtualKitchen.auth.exception.AuthException;
import com.processVisualisation.virtualKitchen.common.logging.LogAction;
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
 * REST controller for a recipe's storyboard: one combined image that tells the whole recipe in panels, generated as a
 * background job that can be polled. Delegates to {@link RecipeStoryboardJobService}.
 */
@RestController
@LogAction("storyboard")
@RequestMapping("/api/v1/recipes/{recipeId}/storyboard")
public class RecipeStoryboardController {

    private final RecipeStoryboardJobService storyboardJobService;
    private final StoryboardStepFixService stepFixService;
    private final RunModelChoices runModelChoices;

    public RecipeStoryboardController(RecipeStoryboardJobService storyboardJobService, StoryboardStepFixService stepFixService,
                                      RunModelChoices runModelChoices) {
        this.storyboardJobService = storyboardJobService;
        this.stepFixService = stepFixService;
        this.runModelChoices = runModelChoices;
    }

    /**
     * Starts a storyboard for the recipe (owner only) and returns immediately; poll {@link #getJobStatus} for
     * progress. If one is already running for the recipe, that one is returned with {@code reused=true}.
     * <p>
     * The optional body names the models picked for this run (text for the panels and the image prompt, image for the
     * picture), whether to plan again after a failed attempt, and whether to replace images steps already have.
     */
    @PostMapping("/jobs")
    public ApiResponse<RecipeStoryboardResponseDTO> startJob(@PathVariable Long recipeId,
                                                            @RequestBody(required = false) StoryboardJobRequestDTO request) {
        Long userId = currentUserId();
        RecipeStoryboardResponseDTO data;
        if (request != null && request.getMode() != null) {
            StoryboardMode mode = request.getMode();
            data = runModelChoices.startWith(request.getModelKeys(), () -> storyboardJobService.startJob(userId, recipeId, mode));
        } else {
            boolean regenerate = request != null && request.isRegenerate();
            boolean overwriteExisting = request != null && request.isOverwriteExisting();
            data = runModelChoices.startWith(request != null ? request.getModelKeys() : null,
                    () -> storyboardJobService.startJob(userId, recipeId, regenerate, overwriteExisting));
        }
        return build(data, "Storyboard generation started");
    }

    /** The status (and, once done, the panels and image) of a storyboard generation the caller started. */
    @GetMapping("/jobs/{jobId}")
    public ApiResponse<RecipeStoryboardResponseDTO> getJobStatus(@PathVariable Long recipeId, @PathVariable String jobId) {
        return build(storyboardJobService.getJobStatus(currentUserId(), recipeId, jobId), "Storyboard generation status");
    }

    /** The recipe's storyboard (its latest completed generation); {@code data} is null when it has none. */
    @GetMapping
    public ApiResponse<RecipeStoryboardResponseDTO> getStoryboard(@PathVariable Long recipeId) {
        return build(storyboardJobService.findLatest(currentUserId(), recipeId).orElse(null), "Recipe storyboard");
    }

    /**
     * Redraws one step's image from the cook's feedback (owner only) and returns at once; poll {@link #getFix}. A fix
     * already running for the step is returned with {@code reused=true}.
     */
    @PostMapping("/steps/{stepId}/fix")
    public ApiResponse<StoryboardStepFixResponseDTO> fixStep(@PathVariable Long recipeId, @PathVariable String stepId,
                                                             @RequestBody StoryboardStepFixRequestDTO request) {
        Long userId = currentUserId();
        StoryboardStepFixResponseDTO data = runModelChoices.startWith(request.getModelKeys(),
                () -> stepFixService.startFix(userId, recipeId, request.getProcessId(), stepId, request.getFeedback()));
        return build(data, "Step image fix started");
    }

    /** The status (and, once done, the new image) of a step image fix the caller started. */
    @GetMapping("/fixes/{fixId}")
    public ApiResponse<StoryboardStepFixResponseDTO> getFix(@PathVariable Long recipeId, @PathVariable String fixId) {
        return build(stepFixService.getFix(currentUserId(), recipeId, fixId), "Step image fix status");
    }

    /** Puts the step back to the prompt and image it had before the fix. */
    @PostMapping("/fixes/{fixId}/revert")
    public ApiResponse<StoryboardStepFixResponseDTO> revertFix(@PathVariable Long recipeId, @PathVariable String fixId) {
        return build(stepFixService.revert(currentUserId(), recipeId, fixId), "Step image fix undone");
    }

    private Long currentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                : null;
        if (!(principal instanceof Long userId)) {
            throw new AuthException("Authentication required to use the recipe storyboard", HttpStatus.UNAUTHORIZED);
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
