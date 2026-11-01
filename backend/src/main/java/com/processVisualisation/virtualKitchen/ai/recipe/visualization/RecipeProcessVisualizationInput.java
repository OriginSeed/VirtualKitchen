package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import java.util.List;

/**
 * The semantic, human-readable content one Process STEP contributes to its visualization prompt —
 * deliberately excludes React Flow ids/positions/dimensions/handles, which are UI concerns the AI
 * was never meant to see (mirrors the same exclusion the Process-generation prompt already makes).
 *
 * @param action human-readable action label (e.g. "Cut"), resolved from the step's action id
 * @param ingredients every Action On ingredient target, in order
 * @param subprocessNames every Action On subprocess this step references, by name (its Process
 *                        document's {@code name} is also its summary/output — see {@code Process}'s
 *                        own javadoc — so no deeper walk into the subprocess's own steps is needed for V1)
 * @param actionDescription the step's natural-language description of what it does
 * @param expectedOutput the step's expected resulting state
 * @param temperature free-text temperature, or blank
 * @param flameLevel human-readable flame level label, or blank
 * @param duration human-readable duration (e.g. "5 minutes"), or blank
 * @param previousExpectedOutput the immediately preceding STEP's expected output, for text-based
 *                               visual continuity — null for the first step of the process
 * @param actionId the step's catalog action id (e.g. "cut"), used only to choose the image's generation profile
 *                 ({@link VisualizationImageProfiles}); never part of the prompt. Null when unknown.
 */
public record RecipeProcessVisualizationInput(
        String action,
        List<IngredientTarget> ingredients,
        List<String> subprocessNames,
        String actionDescription,
        String expectedOutput,
        String temperature,
        String flameLevel,
        String duration,
        String previousExpectedOutput,
        String actionId
) {
    /** An input without an action id; its image uses the default generation profile. */
    public RecipeProcessVisualizationInput(String action, List<IngredientTarget> ingredients, List<String> subprocessNames,
                                           String actionDescription, String expectedOutput, String temperature,
                                           String flameLevel, String duration, String previousExpectedOutput) {
        this(action, ingredients, subprocessNames, actionDescription, expectedOutput, temperature, flameLevel,
                duration, previousExpectedOutput, null);
    }

    /**
     * One Action On ingredient target, with its per-ingredient quantity/unit/preparation style —
     * these are never step-level, since the same step can prepare several ingredients differently.
     */
    public record IngredientTarget(String name, String quantity, String unit, String preparationStyle) {
    }
}
