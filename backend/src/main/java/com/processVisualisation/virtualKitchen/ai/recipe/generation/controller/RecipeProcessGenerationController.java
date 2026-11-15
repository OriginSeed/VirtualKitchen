package com.processVisualisation.virtualKitchen.ai.recipe.generation.controller;

import com.processVisualisation.virtualKitchen.common.logging.LogAction;

import com.processVisualisation.virtualKitchen.ai.platform.preference.RunModelChoices;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessGenerationJobService;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.RecipeProcessGenerationJobResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.RecipeProcessGenerationRequestDTO;
import com.processVisualisation.virtualKitchen.auth.exception.AuthException;
import com.processVisualisation.virtualKitchen.common.web.ApiResponse;
import jakarta.validation.Valid;
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
 * REST controller exposing AI-driven Process generation (a semantic MAIN
 * process + subprocesses from free-form recipe text), scoped under the same
 * recipe-processes base path as {@code ProcessController}. Async-job only —
 * generation always involves an AI call, so there's no synchronous variant.
 * The result
 * is never persisted by this controller/service; the frontend loads it into
 * the current Recipe working session and the user Saves explicitly.
 */
@RestController
@LogAction("generation")
@RequestMapping("/api/v1/recipes/{recipeId}/processes/generate")
public class RecipeProcessGenerationController {

    private final RecipeProcessGenerationJobService recipeProcessGenerationJobService;
    private final RunModelChoices runModelChoices;

    public RecipeProcessGenerationController(RecipeProcessGenerationJobService recipeProcessGenerationJobService,
                                             RunModelChoices runModelChoices) {
        this.recipeProcessGenerationJobService = recipeProcessGenerationJobService;
        this.runModelChoices = runModelChoices;
    }

    /**
     * Starts an async Process generation job and returns immediately — the actual AI call runs on
     * a background task pool. Poll {@link #getJobStatus} with the returned jobId for progress/completion.
     * If a generation is already running for this recipe, that job is returned (with
     * {@code reused=true}) instead of starting a second one.
     *
     * @param recipeId the id of the recipe this generation is for (not persisted against by this
     *                 endpoint — only used for job bookkeeping/analytics)
     * @param request  the validated request: recipe text (CREATE), or a change instruction plus the target
     *                 process (EDIT)
     * @return an ApiResponse wrapping the newly started job's id and initial status
     */
    @PostMapping("/jobs")
    public ApiResponse<RecipeProcessGenerationJobResponseDTO> startJob(
            @PathVariable Long recipeId, @Valid @RequestBody RecipeProcessGenerationRequestDTO request
    ) {
        Long userId = currentUserId();
        // The models picked in the UI for this run (none: the account setting).
        RecipeProcessGenerationJobResponseDTO data = runModelChoices.startWith(request.getModelKeys(),
                () -> recipeProcessGenerationJobService.startJob(userId, recipeId, request));
        return build(data, "Process generation job started");
    }

    /**
     * Retrieves the current status (and, once complete, result) of a previously started Process
     * generation job.
     *
     * @param jobId the id of the job to look up, as returned by {@link #startJob}
     * @return an ApiResponse wrapping the job's current status/progress/result
     */
    @GetMapping("/jobs/{jobId}")
    public ApiResponse<RecipeProcessGenerationJobResponseDTO> getJobStatus(@PathVariable Long recipeId, @PathVariable String jobId) {
        RecipeProcessGenerationJobResponseDTO data = recipeProcessGenerationJobService.getJobStatus(currentUserId(), recipeId, jobId);
        return build(data, "Process generation job status");
    }

    /**
     * Records that the frontend loaded a completed job's result into the user's working session,
     * so {@code GET /api/v1/recipes/{recipeId}/jobs/active} stops offering it back.
     *
     * @param jobId the id of the completed job whose result was applied
     * @return an empty ApiResponse
     */
    @PostMapping("/jobs/{jobId}/applied")
    public ApiResponse<Void> markResultApplied(@PathVariable Long recipeId, @PathVariable String jobId) {
        recipeProcessGenerationJobService.markResultApplied(currentUserId(), recipeId, jobId);
        return build(null, "Process generation result marked as applied");
    }

    private Long currentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                : null;
        if (!(principal instanceof Long userId)) {
            throw new AuthException("Authentication required to generate a recipe process", HttpStatus.UNAUTHORIZED);
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
