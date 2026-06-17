package com.processVisualisation.virtualKitchen.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StepDefinitionResponseDTO {

    private Long id;
    private String name;
    private String description;
    private String mediaUrl;
    private int estimatedTimeSec;
}
