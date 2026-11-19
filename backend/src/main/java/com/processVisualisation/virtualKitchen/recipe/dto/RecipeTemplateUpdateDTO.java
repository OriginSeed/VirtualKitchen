package com.processVisualisation.virtualKitchen.recipe.dto;

import jakarta.validation.constraints.Size;
import com.processVisualisation.virtualKitchen.common.web.InputLimits;
import lombok.Data;
import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for updating an existing recipe template's name and description.
 */
@Data
public class RecipeTemplateUpdateDTO {

    @NotBlank
    @Size(max = InputLimits.NAME, message = "Name can be at most " + InputLimits.NAME + " characters")
    private String name;

    @Size(max = InputLimits.DESCRIPTION, message = "Description can be at most " + InputLimits.DESCRIPTION + " characters")
    private String description;
}
