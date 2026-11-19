package com.processVisualisation.virtualKitchen.recipe.dto;

import jakarta.validation.constraints.Size;
import com.processVisualisation.virtualKitchen.common.web.InputLimits;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * Request payload for updating an existing {@code Process} via
 * {@code PUT /api/v1/recipes/{recipeId}/processes/{processId}}: its
 * name/description plus a full replacement of its node/edge graph and
 * viewport. {@code type} is intentionally not editable here — a process's
 * MAIN/SUBPROCESS type is fixed at creation.
 */
@Data
public class ProcessUpdateDTO {

    @NotBlank
    @Size(max = InputLimits.NAME, message = "Process name can be at most " + InputLimits.NAME + " characters")
    private String name;

    @Size(max = InputLimits.DESCRIPTION, message = "Description can be at most " + InputLimits.DESCRIPTION + " characters")
    private String description;

    @Size(max = InputLimits.PROCESS_NODES, message = "Too many steps in one process (at most " + InputLimits.PROCESS_NODES + ")")
    private List<ProcessNodeDTO> nodes;
    @Size(max = InputLimits.PROCESS_EDGES, message = "Too many connections in one process (at most " + InputLimits.PROCESS_EDGES + ")")
    private List<ProcessEdgeDTO> edges;
    private ProcessViewportDTO viewport;
}
