package com.processVisualisation.virtualKitchen.controller;

import com.processVisualisation.virtualKitchen.dto.RecipeFlowGenerationRequestDTO;
import com.processVisualisation.virtualKitchen.dto.RecipeFlowGenerationResponseDTO;
import com.processVisualisation.virtualKitchen.service.RecipeGenerationService;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recipe")
public class RecipeGenerationController {

    private final RecipeGenerationService recipeGenerationService;

    public RecipeGenerationController(RecipeGenerationService recipeGenerationService) {
        this.recipeGenerationService = recipeGenerationService;
    }

    @PostMapping("/generate-flow")
    public RecipeFlowGenerationResponseDTO generateFlow(@Valid @RequestBody RecipeFlowGenerationRequestDTO request) {
        return recipeGenerationService.generateFlow(request.getRecipe());
    }
}
