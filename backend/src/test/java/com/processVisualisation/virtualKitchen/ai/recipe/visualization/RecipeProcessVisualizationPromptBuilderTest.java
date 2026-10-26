package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the two prompt-shaping rules the brief calls out explicitly: a step with more than one
 * Action On ingredient must ask for a single composite image (never multiple images), and a
 * previous step's expected output must be surfaced as the continuity signal when present.
 */
class RecipeProcessVisualizationPromptBuilderTest {

    private final RecipeProcessVisualizationPromptBuilder builder = new RecipeProcessVisualizationPromptBuilder();

    @Test
    void buildFlowUserPrompt_severalSteps_asksForOneStoryboardImageEndingWithTheOutput() {
        RecipeProcessVisualizationInput cut = new RecipeProcessVisualizationInput(
                "Cut", List.of(), List.of(), "Cut the chicken", "Cut Chicken", "", "", "", null);
        RecipeProcessVisualizationInput marinate = new RecipeProcessVisualizationInput(
                "Marinate", List.of(), List.of(), "Coat in yogurt", "Marinated Chicken", "", "", "30 minutes", "Cut Chicken");

        String prompt = builder.buildFlowUserPrompt("Marinated Chicken", List.of(cut, marinate));

        assertTrue(prompt.contains("making Marinated Chicken"));
        assertTrue(prompt.contains("ONE image laid out as a storyboard of 3 small panels"), prompt);
        assertTrue(prompt.contains("final panel showing the finished Marinated Chicken"));
        assertTrue(prompt.indexOf("Cut the chicken") < prompt.indexOf("Coat in yogurt"), "steps are listed in cooking order");
    }

    @Test
    void buildBatchUserPrompt_listsPreparationsThenMainStepsByIdWithStoryboardsAndContinuity() {
        RecipeProcessVisualizationInput cut = new RecipeProcessVisualizationInput(
                "Cut", List.of(), List.of(), "Cut the chicken", "Cut Chicken", "", "", "", null);
        RecipeProcessVisualizationInput marinate = new RecipeProcessVisualizationInput(
                "Marinate", List.of(), List.of(), "Coat in yogurt", "Marinated Chicken", "", "", "", "Cut Chicken");
        RecipeProcessVisualizationInput heat = new RecipeProcessVisualizationInput(
                "Heat", List.of(), List.of(), "Heat the oil", "Hot oil", "", "Medium", "", null);
        RecipeProcessVisualizationInput fry = new RecipeProcessVisualizationInput(
                "Fry", List.of(), List.of("Marinated Chicken"), "Fry the chicken", "Golden chicken", "", "", "", "Hot oil");

        String prompt = builder.buildBatchUserPrompt(List.of(
                new RecipeProcessVisualizationPromptBuilder.BatchTarget("m1", stepTarget("n1", heat)),
                new RecipeProcessVisualizationPromptBuilder.BatchTarget("p1", new RecipeProcessVisualizationService.VisualTarget(
                        "n0", null, new RecipeProcessVisualizationService.FlowContext("Marinated Chicken", List.of(cut, marinate)))),
                new RecipeProcessVisualizationPromptBuilder.BatchTarget("m2", stepTarget("n2", fry))
        ), List.of());

        assertTrue(prompt.indexOf("p1: making Marinated Chicken") < prompt.indexOf("m1: "), "preparations come first");
        assertTrue(prompt.indexOf("m1: ") < prompt.indexOf("m2: "), "main steps stay in cooking order");
        assertTrue(prompt.contains("ONE image laid out as a storyboard of 3 small panels"), prompt);
        assertTrue(prompt.contains("Continues from: Hot oil"));
        assertTrue(prompt.contains("ids: m1, p1, m2"), prompt);
        assertFalse(prompt.contains("EXISTING PROMPTS"));
    }

    @Test
    void buildBatchUserPrompt_withStyleReferences_asksNewPromptsToMatchThem() {
        RecipeProcessVisualizationInput heat = new RecipeProcessVisualizationInput(
                "Heat", List.of(), List.of(), "Heat the oil", "Hot oil", "", "", "", null);

        String prompt = builder.buildBatchUserPrompt(
                List.of(new RecipeProcessVisualizationPromptBuilder.BatchTarget("m1", stepTarget("n1", heat))),
                List.of("A steel kadai on a marble counter, warm window light"));

        assertTrue(prompt.contains("EXISTING PROMPTS OF THIS RECIPE"));
        assertTrue(prompt.contains("A steel kadai on a marble counter, warm window light"));
    }

    @Test
    void buildBatchSystemPrompt_requiresSelfContainedPromptsSharingOneStyle() {
        String system = builder.buildBatchSystemPrompt();

        assertTrue(system.contains("styleGuide"));
        assertTrue(system.contains("self-contained"));
        assertTrue(system.contains("No text, labels"));
    }

    private static RecipeProcessVisualizationService.VisualTarget stepTarget(String nodeId, RecipeProcessVisualizationInput input) {
        return new RecipeProcessVisualizationService.VisualTarget(nodeId, input, null);
    }

    @Test
    void buildFlowUserPrompt_singleStep_isOneScene() {
        RecipeProcessVisualizationInput chop = new RecipeProcessVisualizationInput(
                "Chop", List.of(), List.of(), "Chop the onions", "Chopped Onions", "", "", "", null);

        String prompt = builder.buildFlowUserPrompt("Chopped Onions", List.of(chop));

        assertFalse(prompt.contains("storyboard"));
        assertTrue(prompt.contains("a single still frame"));
    }

    @Test
    void buildUserPrompt_singleIngredient_doesNotAskForACompositeImage() {
        RecipeProcessVisualizationInput input = new RecipeProcessVisualizationInput(
                "Cut", List.of(new RecipeProcessVisualizationInput.IngredientTarget("Onion", "2", "", "Medium")),
                List.of(), "Cut the onion", "Chopped onion", "", "", "", null);

        String prompt = builder.buildUserPrompt(input);

        assertFalse(prompt.contains("multiple simultaneous"), "a single-ingredient step should not trigger composite-image instructions");
    }

    @Test
    void buildUserPrompt_multipleIngredients_asksForOneCompositeImageNotMultiple() {
        RecipeProcessVisualizationInput input = new RecipeProcessVisualizationInput(
                "Cut",
                List.of(
                        new RecipeProcessVisualizationInput.IngredientTarget("Onion", "2", "", "Medium"),
                        new RecipeProcessVisualizationInput.IngredientTarget("Tomato", "3", "", "Large"),
                        new RecipeProcessVisualizationInput.IngredientTarget("Chili", "1", "", "Fine")),
                List.of(), "Cut the vegetables", "Prepped vegetables", "", "", "", null);

        String prompt = builder.buildUserPrompt(input);

        assertTrue(prompt.contains("multiple simultaneous"));
        assertTrue(prompt.contains("single") && prompt.toLowerCase().contains("composition"));
        assertTrue(prompt.contains("Do not describe separate images"));
    }

    @Test
    void buildUserPrompt_withPreviousExpectedOutput_includesItForContinuity() {
        RecipeProcessVisualizationInput input = new RecipeProcessVisualizationInput(
                "Fry", List.of(), List.of(), "Fry the onions until golden brown", "Golden-brown fried onions",
                "", "", "", "Chopped onions");

        String prompt = builder.buildUserPrompt(input);

        assertTrue(prompt.contains("Chopped onions"));
        assertTrue(prompt.contains("visual continuity"));
    }

    @Test
    void buildUserPrompt_firstStep_hasNoPreviousStepText() {
        RecipeProcessVisualizationInput input = new RecipeProcessVisualizationInput(
                "Cut", List.of(), List.of(), "Cut the onion", "Chopped onion", "", "", "", null);

        String prompt = builder.buildUserPrompt(input);

        assertTrue(prompt.contains("first step"));
    }
}
