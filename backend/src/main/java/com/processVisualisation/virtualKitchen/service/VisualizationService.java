package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.dto.VisualizationResponseDTO;

public interface VisualizationService {
    VisualizationResponseDTO generateVisualization(String flowId);
}
