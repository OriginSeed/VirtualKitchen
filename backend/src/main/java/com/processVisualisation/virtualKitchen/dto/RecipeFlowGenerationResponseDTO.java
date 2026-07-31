package com.processVisualisation.virtualKitchen.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecipeFlowGenerationResponseDTO {

    private List<Map<String, Object>> nodes;
    private List<Map<String, Object>> edges;
}
