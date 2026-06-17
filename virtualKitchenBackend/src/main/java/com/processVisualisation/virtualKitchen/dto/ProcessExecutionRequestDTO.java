package com.processVisualisation.virtualKitchen.dto;

import lombok.Data;

@Data
public class ProcessExecutionRequestDTO {

    private Long processTemplateId;
    private Long userId;
    private Long kitchenId;
}
