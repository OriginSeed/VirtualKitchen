package com.processVisualisation.virtualKitchen.dto;

import com.processVisualisation.virtualKitchen.model.UnitType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class IngredientResponseDTO {

    private Long id;
    private String name;
    private String description;
    private UnitType defaultUnit;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}