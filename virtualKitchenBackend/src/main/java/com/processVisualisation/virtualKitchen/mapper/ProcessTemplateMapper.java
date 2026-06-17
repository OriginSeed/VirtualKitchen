package com.processVisualisation.virtualKitchen.mapper;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.model.ProcessTemplate;
import org.springframework.stereotype.Component;

@Component
public class ProcessTemplateMapper {

    public ProcessTemplate toEntity(ProcessTemplateRequestDTO dto){
        ProcessTemplate pt = new ProcessTemplate();
        pt.setName(dto.getName());
        pt.setDescription(dto.getDescription());
        pt.setCreatedBy(dto.getCreatedBy());
        return pt;
    }

    public ProcessTemplateResponseDTO toDTO(ProcessTemplate pt){
        return ProcessTemplateResponseDTO.builder()
                .id(pt.getId())
                .name(pt.getName())
                .description(pt.getDescription())
                .createdBy(pt.getCreatedBy())
                .createdAt(pt.getCreatedAt())
                .updatedAt(pt.getUpdatedAt())
                .build();
    }
}
