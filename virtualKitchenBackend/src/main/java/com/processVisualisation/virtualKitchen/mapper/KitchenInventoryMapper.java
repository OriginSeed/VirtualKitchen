package com.processVisualisation.virtualKitchen.mapper;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.model.KitchenInventory;
import org.springframework.stereotype.Component;

@Component
public class KitchenInventoryMapper {

    public KitchenInventory toEntity(KitchenInventoryRequestDTO dto){
        KitchenInventory ki = new KitchenInventory();
        ki.setKitchenId(dto.getKitchenId());
        ki.setInventoryId(dto.getInventoryId());
        return ki;
    }

    public KitchenInventoryResponseDTO toDTO(KitchenInventory ki){
        return KitchenInventoryResponseDTO.builder()
                .id(ki.getId())
                .kitchenId(ki.getKitchenId())
                .inventoryId(ki.getInventoryId())
                .build();
    }
}
