package com.processVisualisation.virtualKitchen.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIRequest {

    private String systemPrompt;
    private String userPrompt;
    private String model;
    private Double temperature;
    private Integer maxTokens;
    private String responseFormat;
}
