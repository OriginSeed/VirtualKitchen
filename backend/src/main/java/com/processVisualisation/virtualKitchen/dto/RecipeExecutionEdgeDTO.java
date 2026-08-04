package com.processVisualisation.virtualKitchen.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecipeExecutionEdgeDTO {

    private String from;
    private String to;
}
