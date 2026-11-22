package com.processVisualisation.virtualKitchen.ai.recipe.edit.dto;

import jakarta.validation.Valid;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedRecipeStepDTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One existing node of an {@link EditTargetProcessDTO}: its real node id, its semantic content in the same shape AI
 * generation returns ({@code content.actionOn.steps} holds node ids and {@code content.actionOn.processes} holds
 * process ids as strings), and its outgoing connections so the model can see branches.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EditTargetNodeDTO {

    @NotBlank(message = "node id is required")
    private String nodeId;

    /** The step's number as the editor displays it (STEP nodes only), so "step 3" in an instruction can be resolved. */
    private Integer stepNumber;

    @NotNull(message = "node content is required")
    @Valid
    private GeneratedRecipeStepDTO content;

    /** Targets of the node's ordinary (non-condition) outgoing edges. */
    private List<String> nextNodeIds;

    /** CONDITION only: target of the "Yes" branch. */
    private String yesNodeId;

    /** CONDITION only: target of the "No" branch. */
    private String noNodeId;
}
