package com.processVisualisation.virtualKitchen.ai.recipe.edit.dto;

import com.processVisualisation.virtualKitchen.common.web.InputLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The process an EDIT request modifies, as the frontend serialises it from the working session: its nodes in
 * reading order with their real node ids, plus the other processes of the recipe a step may reference. The backend
 * gives the model short aliases instead of these ids and maps the model's operations back onto them.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EditTargetProcessDTO {

    /** The process's id in the working session (negative while it is still unsaved). */
    private Long processId;

    @Size(max = InputLimits.NAME, message = "Process name can be at most " + InputLimits.NAME + " characters")
    private String name;

    /** Every STEP/CONDITION node of the process, in reading order. */
    @NotNull(message = "targetProcess.nodes is required")
    @Size(max = 80, message = "targetProcess has too many nodes to edit with AI")
    @Valid
    private List<EditTargetNodeDTO> nodes;

    /** The recipe's other processes, which a step's actionOn.processes may reference. */
    private List<EditSubprocessRefDTO> subprocesses;
}
