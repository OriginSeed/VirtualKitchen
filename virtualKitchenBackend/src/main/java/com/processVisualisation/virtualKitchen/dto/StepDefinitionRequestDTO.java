package com.processVisualisation.virtualKitchen.dto;

import lombok.Data;

@Data
public class StepDefinitionRequestDTO {

    private String name;
    private String description;
    private String mediaUrl;
    private int estimatedTimeSec;
}
