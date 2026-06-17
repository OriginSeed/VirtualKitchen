package com.processVisualisation.virtualKitchen.dto;

import com.processVisualisation.virtualKitchen.model.UnitType;
import lombok.Data;

@Data
public class ProcessIngredientUsageRequestDTO {

    private Long processExecutionId;
    private Long ingredientId;
    private double quantityUsed;
    private UnitType unit;
    private double costAtTime;
}
