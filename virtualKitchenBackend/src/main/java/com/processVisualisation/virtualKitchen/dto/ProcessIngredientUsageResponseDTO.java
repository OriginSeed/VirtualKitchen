package com.processVisualisation.virtualKitchen.dto;

import com.processVisualisation.virtualKitchen.model.UnitType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProcessIngredientUsageResponseDTO {

    private Long id;
    private Long processExecutionId;
    private Long ingredientId;
    private double quantityUsed;
    private UnitType unit;
    private double costAtTime;
}
