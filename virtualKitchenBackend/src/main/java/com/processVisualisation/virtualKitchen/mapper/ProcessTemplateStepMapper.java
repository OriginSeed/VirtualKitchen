package com.processVisualisation.virtualKitchen.mapper;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.model.ProcessTemplateStep;
import org.springframework.stereotype.Component;

@Component
public class ProcessTemplateStepMapper {

    public ProcessTemplateStep toEntity(ProcessTemplateStepRequestDTO dto){
        ProcessTemplateStep step = new ProcessTemplateStep();
        step.setProcessTemplateId(dto.getProcessTemplateId());
        step.setStepDefinitionId(dto.getStepDefinitionId());
        step.setStepOrder(dto.getStepOrder());
        return step;
    }

    public ProcessTemplateStepResponseDTO toDTO(ProcessTemplateStep step){
        return ProcessTemplateStepResponseDTO.builder()
                .id(step.getId())
                .processTemplateId(step.getProcessTemplateId())
                .stepDefinitionId(step.getStepDefinitionId())
                .stepOrder(step.getStepOrder())
                .build();
    }
}
