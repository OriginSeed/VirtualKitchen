package com.processVisualisation.virtualKitchen.dto;

import lombok.Data;
import jakarta.validation.constraints.NotNull;

@Data
public class ProcessTemplateStepRequestDTO {

    @NotNull
    private Long processTemplateId;

    @NotNull
    private Long stepDefinitionId;

    @NotNull
    private Integer stepOrder;
}
