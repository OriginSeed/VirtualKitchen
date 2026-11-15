package com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * "Approve &amp; Continue". {@code approvedRevision} is the recipe process revision the editor had
 * just saved when the user approved; approval is refused if the saved recipe has moved on since,
 * so downstream tasks never start from a state the user didn't see.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecipeAiWorkflowApproveRequestDTO {

    @NotNull(message = "approvedRevision is required")
    private Long approvedRevision;

    /**
     * Models picked again at approval (capability name to model key; blank = the account setting). Null or absent
     * keeps the models the run was started with.
     */
    private Map<String, String> modelKeys;

    /**
     * Steps that already have an image or narration: true regenerates and replaces them, false keeps them. Null or
     * absent keeps what the run was started with.
     */
    private Boolean overwriteExisting;

    public RecipeAiWorkflowApproveRequestDTO(Long approvedRevision) {
        this.approvedRevision = approvedRevision;
    }
}
