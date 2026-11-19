package com.processVisualisation.virtualKitchen.store.dto;

import jakarta.validation.constraints.Size;
import com.processVisualisation.virtualKitchen.common.web.InputLimits;
import com.processVisualisation.virtualKitchen.store.units.UnitType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Validated request payload for creating a new ingredient catalog entry,
 * requiring a name and a default {@link UnitType}.
 */
@Data
public class IngredientRequestDTO {

    @NotBlank(message = "Name is required")
    @Size(max = InputLimits.NAME, message = "Name can be at most " + InputLimits.NAME + " characters")
    private String name;

    @Size(max = InputLimits.DESCRIPTION, message = "Description can be at most " + InputLimits.DESCRIPTION + " characters")
    private String description;

    @NotNull(message = "Default unit is required")
    private UnitType defaultUnit;

    @Size(max = InputLimits.URL)
    private String imageUrl;
}