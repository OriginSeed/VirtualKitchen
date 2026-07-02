package com.processVisualisation.virtualKitchen.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class VisualizationRequestDTO {
    private Long processTemplateId;
    private List<Map<String, Object>> nodes;
    private List<Map<String, Object>> edges;
}
