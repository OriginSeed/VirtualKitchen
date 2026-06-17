package com.processVisualisation.virtualKitchen.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProcessTemplateStepResponseDTO {

    private Long id;
    private Long processTemplateId;
    private Long stepDefinitionId;
    private int stepOrder;
}
