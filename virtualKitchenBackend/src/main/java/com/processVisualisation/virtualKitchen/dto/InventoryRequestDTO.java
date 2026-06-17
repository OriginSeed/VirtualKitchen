package com.processVisualisation.virtualKitchen.dto;

import com.processVisualisation.virtualKitchen.model.ItemType;
import com.processVisualisation.virtualKitchen.model.UnitType;
import lombok.Data;

@Data
public class InventoryRequestDTO {

    private Long userId;
    private ItemType itemType;
    private Long itemId;
    private double quantity;
    private UnitType unit;
}
