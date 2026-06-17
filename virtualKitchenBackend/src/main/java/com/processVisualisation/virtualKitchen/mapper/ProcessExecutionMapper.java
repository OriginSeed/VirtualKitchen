package com.processVisualisation.virtualKitchen.mapper;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.model.ProcessExecution;
import com.processVisualisation.virtualKitchen.model.ProcessStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class ProcessExecutionMapper {

    public ProcessExecution toEntity(ProcessExecutionRequestDTO dto){
        ProcessExecution pe = new ProcessExecution();
        pe.setProcessTemplateId(dto.getProcessTemplateId());
        pe.setUserId(dto.getUserId());
        pe.setKitchenId(dto.getKitchenId());
        pe.setStatus(ProcessStatus.NOT_STARTED);
        pe.setStartedAt(LocalDateTime.now());
        return pe;
    }

    public ProcessExecutionResponseDTO toDTO(ProcessExecution pe){
        return ProcessExecutionResponseDTO.builder()
                .id(pe.getId())
                .processTemplateId(pe.getProcessTemplateId())
                .userId(pe.getUserId())
                .kitchenId(pe.getKitchenId())
                .status(pe.getStatus())
                .startedAt(pe.getStartedAt())
                .completedAt(pe.getCompletedAt())
                .generatedMediaUrl(pe.getGeneratedMediaUrl())
                .build();
    }
}
