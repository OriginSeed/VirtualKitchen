package com.processVisualisation.virtualKitchen.dto;

import com.processVisualisation.virtualKitchen.model.ItemType;
import com.processVisualisation.virtualKitchen.model.UnitType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class InventoryResponseDTO {

    private Long id;
    private Long userId;
    private ItemType itemType;
    private Long itemId;
    private double quantity;
    private UnitType unit;
    private LocalDateTime lastUpdated;
}
