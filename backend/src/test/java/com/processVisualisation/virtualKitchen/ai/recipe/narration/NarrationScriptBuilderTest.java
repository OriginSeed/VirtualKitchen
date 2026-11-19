package com.processVisualisation.virtualKitchen.ai.recipe.narration;

import com.processVisualisation.virtualKitchen.ai.recipe.narration.NarrationScriptBuilder.NarrationScript;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationInput;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationInput.IngredientTarget;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NarrationScriptBuilderTest {

    private final NarrationProperties properties = new NarrationProperties();
    private final NarrationScriptBuilder builder = new NarrationScriptBuilder(properties);

    @Test
    void speaksTheDescriptionConditionsAndExpectedResult() {
        NarrationScript script = builder.build(new RecipeProcessVisualizationInput(
                "Fry", List.of(), List.of(), "Fry the onions in the oil", "golden brown onions",
                "180 °C", "Medium", "5 minutes", "ignored previous output"));

        assertEquals("Fry the onions in the oil. Do this for 5 minutes, at 180 degrees Celsius, on medium heat. "
                + "Expected result: golden brown onions.", script.text());
        assertTrue(script.narratable());
        assertEquals(64, script.hash().length());
    }

    @Test
    void fallsBackToActionAndTargetsWhenThereIsNoDescription() {
        NarrationScript script = builder.build(new RecipeProcessVisualizationInput(
                "Chop", List.of(new IngredientTarget("Onion", "1", "piece", ""), new IngredientTarget("Garlic", "", "", "")),
                List.of("Tomato sauce"), "", "", "", "", "", null));

        assertEquals("Chop onion, garlic and Tomato sauce.", script.text());
    }

    @Test
    void sameContentHashesTheSameAndAnyEditChangesTheHash() {
        NarrationScript original = builder.build(input("Add two tablespoons of oil to the pan."));

        assertEquals(original.hash(), builder.build(input("  Add two tablespoons of oil   to the pan  ")).hash());
        assertNotEquals(original.hash(), builder.build(input("Add three tablespoons of oil to the pan.")).hash());
    }

    @Test
    void previousStepContextDoesNotAffectTheScript() {
        RecipeProcessVisualizationInput a = new RecipeProcessVisualizationInput("Add", List.of(), List.of(), "Stir", "", "", "", "", "x");
        RecipeProcessVisualizationInput b = new RecipeProcessVisualizationInput("Add", List.of(), List.of(), "Stir", "", "", "", "", "y");

        assertEquals(builder.build(a).hash(), builder.build(b).hash(),
                "editing the previous step must not make this step's narration stale");
    }

    @Test
    void emptyStepIsNotNarratable() {
        NarrationScript script = builder.build(new RecipeProcessVisualizationInput("", List.of(), List.of(), "", "", "", "", "", null));
        assertFalse(script.narratable());
        assertEquals("", script.hash());
    }

    @Test
    void longScriptsAreCutAtAWordBoundary() {
        properties.setMaxScriptChars(20);
        assertEquals("Stir the soup slowly", builder.build(input("Stir the soup slowly with a wooden spoon")).text());
    }

    @Test
    void aStepSummaryIsSpokenAsItIs() {
        NarrationScript script = builder.build(new RecipeProcessVisualizationInput("Fry", List.of(), List.of(),
                "Fry the onions", "golden onions", "", "Medium", "5 minutes", null, "fry",
                "Once the oil shimmers, slide in the onions and stir until they turn golden"));

        assertEquals("Once the oil shimmers, slide in the onions and stir until they turn golden.", script.text());
    }

    @Test
    void aStepWithoutASummaryKeepsItsScriptAndItsHash() {
        RecipeProcessVisualizationInput withoutSummary = input("Stir the soup");
        RecipeProcessVisualizationInput blankSummary = new RecipeProcessVisualizationInput("Add", List.of(), List.of(),
                "Stir the soup", "", "", "", "", null, null, "  ");

        assertEquals(builder.build(withoutSummary).hash(), builder.build(blankSummary).hash(),
                "existing narration stays valid for steps that have no summary");
    }

    private static RecipeProcessVisualizationInput input(String description) {
        return new RecipeProcessVisualizationInput("Add", List.of(), List.of(), description, "", "", "", "", null);
    }
}
