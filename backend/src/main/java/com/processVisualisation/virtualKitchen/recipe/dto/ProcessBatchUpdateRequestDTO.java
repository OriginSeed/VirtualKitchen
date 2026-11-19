package com.processVisualisation.virtualKitchen.recipe.dto;

import jakarta.validation.constraints.Size;
import com.processVisualisation.virtualKitchen.common.web.InputLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Request body for {@code PUT /api/v1/recipes/{recipeId}/processes}: a
 * recipe-level save of the complete current editing snapshot — the MAIN
 * process and every SUBPROCESS the Recipe Tool currently has loaded, saved
 * as one logical operation instead of one HTTP call per process. Every item
 * is validated (via {@code ProcessValidator}, same as the single-process
 * update) before any of them are persisted, so a single invalid process
 * rejects the whole batch rather than leaving the recipe half-saved.
 * <p>
 * {@link #baseRevision} is the recipe's process revision the client's snapshot was based on; when
 * present, the save is rejected with 409 if the recipe has been saved since (optimistic
 * concurrency). Omitted by older clients, which then get the previous last-write-wins behavior.</p>
 */
@Data
public class ProcessBatchUpdateRequestDTO {

    @NotEmpty
    @Size(max = InputLimits.PROCESS_BATCH, message = "Too many processes in one save (at most " + InputLimits.PROCESS_BATCH + ")")
    @Valid
    private List<ProcessBatchItemDTO> processes;

    private Long baseRevision;
}
