package com.processVisualisation.virtualKitchen.ai.recipe.generation.dto;

import com.processVisualisation.virtualKitchen.ai.recipe.edit.dto.EditTargetProcessDTO;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request payload for AI process generation. In CREATE mode (the default) {@code recipeText} is free-form recipe
 * text converted into a semantic Process structure (MAIN process plus subprocesses); in EDIT mode it is a
 * natural-language change applied to {@code targetProcess}. The target recipe id is a path variable on the
 * endpoint, not part of this body.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecipeProcessGenerationRequestDTO {

    @NotBlank(message = "recipeText is required")
    private String recipeText;

    /**
     * Optional client-generated id (e.g. a UUID) used as the AI request's idempotency key, so an
     * accidental double-submit of the same generation click never reserves/charges credits twice.
     */
    private String clientRequestId;

    /** CREATE when absent, so older clients keep their behaviour. */
    private ProcessGenerationMode mode;

    /** EDIT only: the process being changed. */
    @Valid
    private EditTargetProcessDTO targetProcess;

    /** EDIT only, optional: the node selected in the editor, so "this step" / "the previous step" can be resolved. */
    private String selectedNodeId;

    /**
     * Models the user picked for this run (capability name to model key; blank = the account setting). Applied by
     * the controller around the job start (see {@code AiRunModels}); null keeps the account setting.
     */
    private Map<String, String> modelKeys;

    public RecipeProcessGenerationRequestDTO(String recipeText, String clientRequestId) {
        this.recipeText = recipeText;
        this.clientRequestId = clientRequestId;
    }

    @JsonIgnore
    public ProcessGenerationMode effectiveMode() {
        return mode == null ? ProcessGenerationMode.CREATE : mode;
    }

    @JsonIgnore
    @AssertTrue(message = "targetProcess is required in EDIT mode")
    public boolean isTargetProcessPresentForEdit() {
        return effectiveMode() != ProcessGenerationMode.EDIT || targetProcess != null;
    }
}
