package com.processVisualisation.virtualKitchen.ai.recipe.workflow.controller;

import com.processVisualisation.virtualKitchen.ai.platform.preference.RunModelsRequestDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.RecipeAiWorkflowService;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto.RecipeAiTaskSelectionDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto.RecipeAiWorkflowApproveRequestDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto.RecipeAiWorkflowCreateRequestDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto.RecipeAiWorkflowEstimateDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto.RecipeAiWorkflowResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST controller for AI Recipe Creation: one user-controlled workflow over process generation,
 * step visuals and narration. The recipe process is generated first; visuals and narration start
 * only when the user approves the (possibly edited) process. Poll {@link #getStatus} for progress;
 * {@code GET /api/v1/recipes/{recipeId}/jobs/active} also returns the open workflow after a reload.
 */
@RestController
@RequestMapping("/api/v1/recipes/{recipeId}/ai-workflows")
public class RecipeAiWorkflowController {

    private final RecipeAiWorkflowService workflowService;

    public RecipeAiWorkflowController(RecipeAiWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /** Approximate credits for a task selection, before anything is started. */
    @PostMapping("/estimate")
    public ApiResponse<RecipeAiWorkflowEstimateDTO> estimate(@PathVariable Long recipeId, @RequestBody RecipeAiTaskSelectionDTO selection) {
        return build(workflowService.estimate(currentUserId(), recipeId, selection), "AI usage estimate");
    }

    /** Starts AI Recipe Creation, or returns the recipe's already-open workflow ({@code reused=true}). */
    @PostMapping
    public ApiResponse<RecipeAiWorkflowResponseDTO> create(@PathVariable Long recipeId, @Valid @RequestBody RecipeAiWorkflowCreateRequestDTO request) {
        return build(workflowService.create(currentUserId(), recipeId, request), "AI Recipe Creation started");
    }

    @GetMapping("/{workflowId}")
    public ApiResponse<RecipeAiWorkflowResponseDTO> getStatus(@PathVariable Long recipeId, @PathVariable String workflowId) {
        return build(workflowService.getStatus(currentUserId(), recipeId, workflowId), "AI Recipe Creation status");
    }

    /** What approving would cost now, priced against the recipe's currently saved steps. */
    @GetMapping("/{workflowId}/estimate")
    public ApiResponse<RecipeAiWorkflowEstimateDTO> estimateApproval(
            @PathVariable Long recipeId, @PathVariable String workflowId,
            // Models about to be approved with, as query parameters named by capability (TEXT_TO_IMAGE=key), plus
            // overwriteExisting=true|false for the keep-or-replace choice (absent: the run's choice).
            @RequestParam(required = false) Map<String, String> params) {
        Map<String, String> modelKeys = new LinkedHashMap<>(params != null ? params : Map.of());
        String overwrite = modelKeys.remove(OVERWRITE_PARAM);
        return build(workflowService.estimateApproval(currentUserId(), recipeId, workflowId, modelKeys,
                overwrite == null || overwrite.isBlank() ? null : Boolean.parseBoolean(overwrite)), "AI usage estimate");
    }

    static final String OVERWRITE_PARAM = "overwriteExisting";

    /** "Approve &amp; Continue": starts the selected visuals/narration from the saved, approved recipe. */
    @PostMapping("/{workflowId}/approve")
    public ApiResponse<RecipeAiWorkflowResponseDTO> approve(
            @PathVariable Long recipeId, @PathVariable String workflowId, @Valid @RequestBody RecipeAiWorkflowApproveRequestDTO request) {
        return build(workflowService.approve(currentUserId(), recipeId, workflowId, request.getApprovedRevision(),
                        request.getModelKeys(), request.getOverwriteExisting()),
                "Recipe process approved");
    }

    /** Retries only the failed part of one task, optionally with models picked again for it. */
    @PostMapping("/{workflowId}/tasks/{task}/retry")
    public ApiResponse<RecipeAiWorkflowResponseDTO> retryTask(
            @PathVariable Long recipeId, @PathVariable String workflowId, @PathVariable RecipeAiTaskType task,
            @RequestBody(required = false) RunModelsRequestDTO models) {
        return build(workflowService.retryTask(currentUserId(), recipeId, workflowId, task,
                models != null ? models.getModelKeys() : null), "Task retry started");
    }

    /** Discards a workflow waiting for approval (the only point where nothing is still running). */
    @PostMapping("/{workflowId}/discard")
    public ApiResponse<RecipeAiWorkflowResponseDTO> discard(@PathVariable Long recipeId, @PathVariable String workflowId) {
        return build(workflowService.discard(currentUserId(), recipeId, workflowId), "AI Recipe Creation discarded");
    }

    /** Hides a finished workflow's summary. */
    @PostMapping("/{workflowId}/dismiss")
    public ApiResponse<Void> dismiss(@PathVariable Long recipeId, @PathVariable String workflowId) {
        workflowService.dismiss(currentUserId(), recipeId, workflowId);
        return build(null, "AI Recipe Creation dismissed");
    }

    private Long currentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                : null;
        if (!(principal instanceof Long userId)) {
            throw new AuthException("Authentication required for AI Recipe Creation", HttpStatus.UNAUTHORIZED);
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
