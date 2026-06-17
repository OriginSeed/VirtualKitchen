package com.processVisualisation.virtualKitchen.mapper;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.model.Inventory;
import org.springframework.stereotype.Component;

@Component
public class InventoryMapper {

    public Inventory toEntity(InventoryRequestDTO dto){
        Inventory inv = new Inventory();
        inv.setUserId(dto.getUserId());
        inv.setItemType(dto.getItemType());
        inv.setItemId(dto.getItemId());
        inv.setQuantity(dto.getQuantity());
        inv.setUnit(dto.getUnit());
        return inv;
    }

    public InventoryResponseDTO toDTO(Inventory inv){
        return InventoryResponseDTO.builder()
                .id(inv.getId())
                .userId(inv.getUserId())
                .itemType(inv.getItemType())
                .itemId(inv.getItemId())
                .quantity(inv.getQuantity())
                .unit(inv.getUnit())
                .lastUpdated(inv.getLastUpdated())
                .build();
    }
}
