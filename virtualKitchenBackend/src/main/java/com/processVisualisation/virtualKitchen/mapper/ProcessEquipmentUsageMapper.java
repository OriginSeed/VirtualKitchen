package com.processVisualisation.virtualKitchen.mapper;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.model.ProcessEquipmentUsage;
import org.springframework.stereotype.Component;

@Component
public class ProcessEquipmentUsageMapper {

    public ProcessEquipmentUsage toEntity(ProcessEquipmentUsageRequestDTO dto){
        ProcessEquipmentUsage p = new ProcessEquipmentUsage();
        p.setProcessExecutionId(dto.getProcessExecutionId());
        p.setEquipmentId(dto.getEquipmentId());
        p.setUsageDurationSec(dto.getUsageDurationSec());
        p.setCostAtTime(dto.getCostAtTime());
        return p;
    }

    public ProcessEquipmentUsageResponseDTO toDTO(ProcessEquipmentUsage p){
        return ProcessEquipmentUsageResponseDTO.builder()
                .id(p.getId())
                .processExecutionId(p.getProcessExecutionId())
                .equipmentId(p.getEquipmentId())
                .usageDurationSec(p.getUsageDurationSec())
                .costAtTime(p.getCostAtTime())
                .build();
    }
}
