package com.processVisualisation.virtualKitchen.dto;

import com.processVisualisation.virtualKitchen.model.UnitType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class IngredientUpdateDTO {

    @NotBlank
    private String name;

    private String description;

    @NotNull
    private UnitType defaultUnit;
}