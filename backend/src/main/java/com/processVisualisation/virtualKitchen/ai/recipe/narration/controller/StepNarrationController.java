package com.processVisualisation.virtualKitchen.ai.recipe.narration.controller;

import com.processVisualisation.virtualKitchen.common.logging.LogAction;

import com.processVisualisation.virtualKitchen.ai.recipe.narration.StepNarrationService;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.dto.NarrationBatchRequestDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.dto.NarrationEnsureRequestDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.dto.StepNarrationResponseDTO;
import com.processVisualisation.virtualKitchen.auth.exception.AuthException;
import com.processVisualisation.virtualKitchen.common.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * REST controller for spoken narration of a Process's STEP nodes, used by the step slideshow.
 * Responses are provider-independent: which TTS engine produced the audio is metadata only.
 * Generation is lazy and asynchronous: {@code POST} returns {@code GENERATING} and the client
 * polls {@code GET} until the step is {@code READY} (or {@code FAILED}).
 */
@RestController
@LogAction("narration")
@RequestMapping("/api/v1/recipes/{recipeId}/processes/{processId}")
public class StepNarrationController {

    private final StepNarrationService stepNarrationService;

    public StepNarrationController(StepNarrationService stepNarrationService) {
        this.stepNarrationService = stepNarrationService;
    }

    /** Narration state of every step in the process, in step order. Never triggers generation. */
    @GetMapping("/narrations")
    public ApiResponse<List<StepNarrationResponseDTO>> list(@PathVariable Long recipeId, @PathVariable Long processId) {
        return build(stepNarrationService.list(currentUserId(), recipeId, processId), "Process narrations");
    }

    /** Ensures narration for several steps (all when {@code stepIds} is omitted); each step succeeds or fails alone. */
    @PostMapping("/narrations")
    public ApiResponse<List<StepNarrationResponseDTO>> ensureAll(
            @PathVariable Long recipeId, @PathVariable Long processId,
            @RequestBody(required = false) NarrationBatchRequestDTO request) {
        List<String> stepIds = request == null ? null : request.getStepIds();
        return build(stepNarrationService.ensureAll(currentUserId(), recipeId, processId, stepIds),
                "Process narration requested");
    }

    /** Narration state of one step. Never triggers generation. */
    @GetMapping("/steps/{stepId}/narration")
    public ApiResponse<StepNarrationResponseDTO> get(
            @PathVariable Long recipeId, @PathVariable Long processId, @PathVariable String stepId) {
        return build(stepNarrationService.get(currentUserId(), recipeId, processId, stepId), "Step narration");
    }

    /**
     * Returns the step's narration, generating it only if there is none for the step's current text.
     * Concurrent calls never generate twice.
     */
    @PostMapping("/steps/{stepId}/narration")
    public ApiResponse<StepNarrationResponseDTO> ensure(
            @PathVariable Long recipeId, @PathVariable Long processId, @PathVariable String stepId,
            @RequestBody(required = false) NarrationEnsureRequestDTO request) {
        boolean force = request != null && request.isForce();
        return build(stepNarrationService.ensure(currentUserId(), recipeId, processId, stepId, force),
                "Step narration requested");
    }

    /** Deletes the step's narration and its audio (recipe owner only). */
    @DeleteMapping("/steps/{stepId}/narration")
    public ApiResponse<Void> delete(
            @PathVariable Long recipeId, @PathVariable Long processId, @PathVariable String stepId) {
        stepNarrationService.delete(currentUserId(), recipeId, processId, stepId);
        return build(null, "Step narration deleted");
    }

    private Long currentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                : null;
        if (!(principal instanceof Long userId)) {
            throw new AuthException("Authentication required for step narration", HttpStatus.UNAUTHORIZED);
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
