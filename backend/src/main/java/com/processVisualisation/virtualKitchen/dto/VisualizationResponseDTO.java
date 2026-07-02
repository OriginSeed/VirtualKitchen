package com.processVisualisation.virtualKitchen.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class VisualizationResponseDTO {
    private Long processTemplateId;
    private String message;
    private List<VisualizationClipResponseDTO> clips;
    private VisualizationClipResponseDTO finalClip;
}
