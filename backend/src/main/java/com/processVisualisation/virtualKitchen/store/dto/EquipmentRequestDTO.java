package com.processVisualisation.virtualKitchen.store.dto;

import jakarta.validation.constraints.Size;
import com.processVisualisation.virtualKitchen.common.web.InputLimits;
import lombok.Data;
import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for creating a new equipment catalog entry.
 */
@Data
public class EquipmentRequestDTO {
    @NotBlank
    @Size(max = InputLimits.NAME, message = "Name can be at most " + InputLimits.NAME + " characters")
    private String name;
    @Size(max = InputLimits.DESCRIPTION, message = "Description can be at most " + InputLimits.DESCRIPTION + " characters")
    private String description;
}
