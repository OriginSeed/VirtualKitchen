package com.processVisualisation.virtualKitchen.recipe.dto;

import jakarta.validation.constraints.Size;
import com.processVisualisation.virtualKitchen.common.web.InputLimits;
import lombok.Data;
import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for creating a new recipe template: its name and description. The owner is always the
 * authenticated caller; {@code createdBy} is overwritten from the verified JWT (a value sent by the client is
 * ignored, and older clients that still send it keep working).
 */
@Data
public class RecipeTemplateRequestDTO {

    @NotBlank
    @Size(max = InputLimits.NAME, message = "Name can be at most " + InputLimits.NAME + " characters")
    private String name;

    @Size(max = InputLimits.DESCRIPTION, message = "Description can be at most " + InputLimits.DESCRIPTION + " characters")
    private String description;

    private Long createdBy;
}
