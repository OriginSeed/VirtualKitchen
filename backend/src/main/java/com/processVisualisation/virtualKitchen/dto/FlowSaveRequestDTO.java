package com.processVisualisation.virtualKitchen.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class FlowSaveRequestDTO {
    private String flowId;
    private String userId;
    private Long templateId;
    private List<Map<String, Object>> nodes;
    private List<Map<String, Object>> edges;
}
