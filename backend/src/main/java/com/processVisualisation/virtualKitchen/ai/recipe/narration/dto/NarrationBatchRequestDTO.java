package com.processVisualisation.virtualKitchen.ai.recipe.narration.dto;

import jakarta.validation.constraints.Size;
import com.processVisualisation.virtualKitchen.common.web.InputLimits;
import lombok.Data;

import java.util.List;

/** Optional body for ensuring narration for several steps of one process at once. */
@Data
public class NarrationBatchRequestDTO {
    /** Steps to ensure; null or empty means every step of the process. */
    @Size(max = InputLimits.NARRATION_STEPS, message = "Too many steps (at most " + InputLimits.NARRATION_STEPS + ")")
    private List<String> stepIds;
}
