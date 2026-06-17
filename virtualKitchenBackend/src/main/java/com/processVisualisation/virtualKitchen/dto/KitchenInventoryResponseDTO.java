package com.processVisualisation.virtualKitchen.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class KitchenInventoryResponseDTO {

    private Long id;
    private Long kitchenId;
    private Long inventoryId;
}
