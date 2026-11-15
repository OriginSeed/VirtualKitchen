package com.processVisualisation.virtualKitchen.recipe.dto;

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
    private String name;

    private String description;

    private Long createdBy;
}
