package com.processVisualisation.virtualKitchen.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class KitchenInventoryRequestDTO {

    @NotNull
    private Long kitchenId;

    @NotNull
    private Long inventoryId;
}
