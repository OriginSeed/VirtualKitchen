package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.service.recipe.RecipeFlowValidationResult;
import com.processVisualisation.virtualKitchen.service.recipe.RecipeValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeValidatorTest {

    private final RecipeValidator validator = new RecipeValidator();

    @Test
    void shouldValidateCorrectGraph() {
        List<Map<String, Object>> nodes = List.of(
                Map.of(
                        "id", "n1",
                        "type", "recipeStepNode",
                        "data", Map.of(
                                "title", "Wash rice",
                                "step", Map.of("notes", "Wash twice")
                        )
                ),
                Map.of(
                        "id", "n2",
                        "type", "recipeStepNode",
                        "data", Map.of(
                                "title", "Cook rice",
                                "step", Map.of("duration", "20 min")
                        )
                )
        );

        List<Map<String, Object>> edges = List.of(
                Map.of(
                        "id", "e1",
                        "source", "n1",
                        "target", "n2",
                        "type", "smoothstep"
                )
        );

        RecipeFlowValidationResult result = validator.validate(nodes, edges);

        assertTrue(result.isValid());
    }

    @Test
    void shouldFailForDuplicateNodeIds() {
        List<Map<String, Object>> nodes = List.of(
                Map.of(
                        "id", "n1",
                        "type", "recipeStepNode",
                        "data", Map.of("title", "A", "step", Map.of())
                ),
                Map.of(
                        "id", "n1",
                        "type", "recipeStepNode",
                        "data", Map.of("title", "B", "step", Map.of())
                )
        );

        RecipeFlowValidationResult result = validator.validate(nodes, List.of());
        assertFalse(result.isValid());
    }

    @Test
    void shouldFailForUnknownNodeType() {
        List<Map<String, Object>> nodes = List.of(
                Map.of(
                        "id", "n1",
                        "type", "unknownNode",
                        "data", Map.of("title", "Bad")
                )
        );

        RecipeFlowValidationResult result = validator.validate(nodes, List.of());
        assertFalse(result.isValid());
    }

    @Test
    void shouldFailForInvalidEdgeReference() {
        List<Map<String, Object>> nodes = List.of(
                Map.of(
                        "id", "n1",
                        "type", "recipeStepNode",
                        "data", Map.of("title", "Only", "step", Map.of())
                )
        );

        List<Map<String, Object>> edges = List.of(
                Map.of(
                        "id", "e1",
                        "source", "n1",
                        "target", "missing"
                )
        );

        RecipeFlowValidationResult result = validator.validate(nodes, edges);
        assertFalse(result.isValid());
    }
}
