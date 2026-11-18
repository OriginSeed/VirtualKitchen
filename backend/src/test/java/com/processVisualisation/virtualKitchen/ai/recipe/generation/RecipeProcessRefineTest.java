package com.processVisualisation.virtualKitchen.ai.recipe.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedRecipeProcessDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedRecipeStepDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.RecipeProcessGenerationResultDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeProcessRefineTest {

    private static final RecipeStepVocabularyProvider VOCABULARY = new RecipeStepVocabularyProvider();

    @Test
    void aRefinedStepKeepsTheIdOfTheOneNodeItCameFrom_aMergedOrRepeatedOneGetsANewId() {
        GeneratedRecipeStepDTO kept = step(List.of("m1"));
        GeneratedRecipeStepDTO merged = step(List.of("m2", "m3"));
        GeneratedRecipeStepDTO added = step(null);
        GeneratedRecipeStepDTO claimedAgain = step(List.of("m1"));
        GeneratedRecipeStepDTO moved = step(List.of("p1"));
        RecipeProcessGenerationResultDTO result = RecipeProcessGenerationResultDTO.builder()
                .subprocesses(List.of(process(moved)))
                .mainProcess(process(kept, merged, added, claimedAgain))
                .build();

        RecipeProcessGenerationService.mapSourceNodes(result, Map.of("m1", "node-a", "m2", "node-b", "m3", "node-c", "p1", "node-p"));

        assertEquals("node-p", moved.getSourceNodeId(), "a step moved between processes keeps its node");
        assertEquals("node-a", kept.getSourceNodeId());
        assertNull(merged.getSourceNodeId());
        assertNull(added.getSourceNodeId());
        assertNull(claimedAgain.getSourceNodeId(), "one node is claimed by one step only");
        assertNull(kept.getSourceAliases(), "the raw aliases are never sent on");
    }

    @Test
    void theRefineSchemaAsksWhereEachStepCameFromAndWhatChanged_andThePromptExplainsIt() throws Exception {
        RecipeProcessOutputSchema schema = new RecipeProcessOutputSchema(VOCABULARY);
        JsonNode root = new ObjectMapper().valueToTree(schema.refineJsonSchema());
        assertTrue(root.at("/required").toString().contains("changes"));
        assertTrue(root.at("/properties/main/properties/steps/items/anyOf/0/properties").has("sourceStepIds"));
        assertTrue(new ObjectMapper().valueToTree(schema.jsonSchema()).at("/properties").has("main"));

        RecipeProcessGenerationPromptBuilder prompts = new RecipeProcessGenerationPromptBuilder(VOCABULARY, schema);
        assertTrue(prompts.buildRefineSystemPrompt().contains("REFINING A COOK'S OWN RECIPE"));
        String initial = prompts.buildRefineInitialPrompt("{\"main\":{}}", "less salt");
        assertTrue(initial.contains("CURRENT RECIPE:\n{\"main\":{}}"));
        assertTrue(initial.contains("COOK'S NOTE:\nless salt"));
    }

    private static GeneratedRecipeStepDTO step(List<String> aliases) {
        GeneratedRecipeStepDTO step = new GeneratedRecipeStepDTO();
        step.setNodeType("STEP");
        step.setSourceAliases(aliases == null ? null : new ArrayList<>(aliases));
        return step;
    }

    private static GeneratedRecipeProcessDTO process(GeneratedRecipeStepDTO... steps) {
        GeneratedRecipeProcessDTO process = new GeneratedRecipeProcessDTO();
        process.setSteps(new ArrayList<>(List.of(steps)));
        return process;
    }
}
