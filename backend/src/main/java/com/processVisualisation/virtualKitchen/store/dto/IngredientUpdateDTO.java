package com.processVisualisation.virtualKitchen.store.dto;

import jakarta.validation.constraints.Size;
import com.processVisualisation.virtualKitchen.common.web.InputLimits;
import com.processVisualisation.virtualKitchen.store.units.UnitType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Validated request payload for updating an existing ingredient's name,
 * description and default {@link UnitType}.
 */
@Data
public class IngredientUpdateDTO {

    @NotBlank
    @Size(max = InputLimits.NAME, message = "Name can be at most " + InputLimits.NAME + " characters")
    private String name;

    @Size(max = InputLimits.DESCRIPTION, message = "Description can be at most " + InputLimits.DESCRIPTION + " characters")
    private String description;

    @NotNull
    private UnitType defaultUnit;

    @Size(max = InputLimits.URL)
    private String imageUrl;
}