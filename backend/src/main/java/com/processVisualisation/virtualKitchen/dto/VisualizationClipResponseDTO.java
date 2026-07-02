package com.processVisualisation.virtualKitchen.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VisualizationClipResponseDTO {
    private Long id;
    private String clipId;
    private String parentClipId;
    private String title;
    private String description;
    private int stepOrder;
    private String mediaUrl;
    private String thumbnailUrl;
    private String status;
    private String cameraAngle;
    private String sourceNodeId;
}
