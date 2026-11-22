package com.processVisualisation.virtualKitchen.recipe.dto;

import jakarta.validation.constraints.Size;
import com.processVisualisation.virtualKitchen.common.web.InputLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * One process's replacement content within a recipe-level batch save (see
 * {@link ProcessBatchUpdateRequestDTO}) — the same fields as
 * {@link ProcessUpdateDTO}, plus the {@code processId} identifying which
 * process this item applies to (implicit in the URL for the single-process
 * {@code PUT /processes/{processId}} endpoint, but explicit here since a
 * batch covers many processes in one request).
 */
@Data
public class ProcessBatchItemDTO {

    @NotNull
    private Long processId;

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
