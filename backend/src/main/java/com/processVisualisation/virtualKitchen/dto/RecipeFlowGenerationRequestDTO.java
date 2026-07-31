package com.processVisualisation.virtualKitchen.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecipeFlowGenerationRequestDTO {

    @NotBlank(message = "recipe is required")
    private String recipe;
}
