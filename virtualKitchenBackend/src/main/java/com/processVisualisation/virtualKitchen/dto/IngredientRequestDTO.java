package com.processVisualisation.virtualKitchen.dto;

import com.processVisualisation.virtualKitchen.model.UnitType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class IngredientRequestDTO {

    @NotBlank(message = "Name is required")
    private String name;

    private String description;

    @NotNull(message = "Default unit is required")
    private UnitType defaultUnit;
}