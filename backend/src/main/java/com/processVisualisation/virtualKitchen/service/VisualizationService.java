package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.dto.VisualizationResponseDTO;

import java.util.List;
import java.util.Map;

public interface VisualizationService {
    VisualizationResponseDTO generateVisualization(Long processTemplateId, List<Map<String, Object>> nodes, List<Map<String, Object>> edges);
}
