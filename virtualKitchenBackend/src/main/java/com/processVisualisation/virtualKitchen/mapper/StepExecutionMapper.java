package com.processVisualisation.virtualKitchen.mapper;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.model.StepExecution;
import com.processVisualisation.virtualKitchen.model.StepStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class StepExecutionMapper {

    public StepExecution toEntity(StepExecutionRequestDTO dto){
        StepExecution step = new StepExecution();
        step.setProcessExecutionId(dto.getProcessExecutionId());
        step.setStepDefinitionId(dto.getStepDefinitionId());
        step.setStatus(StepStatus.NOT_STARTED);
        return step;
    }

    public StepExecutionResponseDTO toDTO(StepExecution step){
        return StepExecutionResponseDTO.builder()
                .id(step.getId())
                .processExecutionId(step.getProcessExecutionId())
                .stepDefinitionId(step.getStepDefinitionId())
                .status(step.getStatus())
                .startedAt(step.getStartedAt())
                .completedAt(step.getCompletedAt())
                .notes(step.getNotes())
                .build();
    }
}
