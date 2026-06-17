package com.processVisualisation.virtualKitchen.mapper;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.model.ProcessIngredientUsage;
import org.springframework.stereotype.Component;

@Component
public class ProcessIngredientUsageMapper {

    public ProcessIngredientUsage toEntity(ProcessIngredientUsageRequestDTO dto){
        ProcessIngredientUsage p = new ProcessIngredientUsage();
        p.setProcessExecutionId(dto.getProcessExecutionId());
        p.setIngredientId(dto.getIngredientId());
        p.setQuantityUsed(dto.getQuantityUsed());
        p.setUnit(dto.getUnit());
        p.setCostAtTime(dto.getCostAtTime());
        return p;
    }

    public ProcessIngredientUsageResponseDTO toDTO(ProcessIngredientUsage p){
        return ProcessIngredientUsageResponseDTO.builder()
                .id(p.getId())
                .processExecutionId(p.getProcessExecutionId())
                .ingredientId(p.getIngredientId())
                .quantityUsed(p.getQuantityUsed())
                .unit(p.getUnit())
                .costAtTime(p.getCostAtTime())
                .build();
    }
}
