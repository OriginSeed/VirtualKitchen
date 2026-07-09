package com.processVisualisation.virtualKitchen.dto;

import lombok.Data;

@Data
public class VisualizationRequestDTO {
    private Long processTemplateId;
    private String flowId;
}
