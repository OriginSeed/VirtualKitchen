package com.processVisualisation.virtualKitchen.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecipeExecutionStepDTO {

    private String id;
    private String action;
    private String ingredientId;
    private String quantity;
    private String unit;
    private String style;
    private String duration;
    private String flame;
    private String temperature;
    private String notes;
}
