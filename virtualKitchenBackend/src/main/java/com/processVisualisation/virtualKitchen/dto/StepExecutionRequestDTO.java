package com.processVisualisation.virtualKitchen.dto;

import lombok.Data;

@Data
public class StepExecutionRequestDTO {

    private Long processExecutionId;
    private Long stepDefinitionId;
}
