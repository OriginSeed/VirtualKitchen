package com.processVisualisation.virtualKitchen.service.recipe;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class RecipeFlowValidationResult {

    private boolean valid;
    private List<String> errors;
}
