package com.processVisualisation.virtualKitchen.dto;

import com.processVisualisation.virtualKitchen.model.StepStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class StepExecutionResponseDTO {

    private Long id;
    private Long processExecutionId;
    private Long stepDefinitionId;
    private StepStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String notes;
}
