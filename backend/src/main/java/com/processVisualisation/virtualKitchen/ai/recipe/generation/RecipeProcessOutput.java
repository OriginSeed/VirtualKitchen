package com.processVisualisation.virtualKitchen.ai.recipe.generation;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

/**
 * The compact JSON the model returns for recipe text -> Process generation, exactly as described by
 * {@link RecipeProcessOutputSchema}: independent preparation flows, each with a named output, plus the one main
 * cooking sequence that uses them. Optional fields are simply absent (null here);
 * {@link RecipeProcessOutputNormalizer} fills the defaults while mapping into the {@code Generated*DTO}s.
 * The aliases accept the pre-v3 shape (mainProcess/subprocesses/processes) so an older-style answer still parses.
 */
public record RecipeProcessOutput(
        List<Preparation> preparations,
        @JsonAlias("mainProcess") Main main
) {

    /** One preparation flow; {@code output} is the named result the main sequence uses (it becomes the process name). */
    public record Preparation(String ref, @JsonAlias("name") String output, List<Node> steps) {}

    /** The single main cooking sequence. */
    public record Main(String name, List<Node> steps) {}

    /** A STEP (nodeType absent or "STEP") or a CONDITION — one flat record, like {@code GeneratedRecipeStepDTO}. */
    public record Node(
            String nodeType,
            String stepId,
            String action,
            String customActionName,
            List<Ingredient> ingredients,
            /* Preparation refs whose output this step uses. */
            @JsonAlias("processes") List<String> uses,
            List<String> fromSteps,
            String actionDescription,
            String expectedOutput,
            Double temperatureValue,
            String temperatureUnit,
            String flameLevel,
            String duration,
            String repeatInterval,
            /* CONDITION: the question. STEP: an optional short title. */
            String title,
            String expectedResult,
            /* STEP: the spoken explanation of the step, continuing from the step before it. */
            String summary
    ) {}

    public record Ingredient(
            String ingredientId,
            Double quantity,
            String unit,
            String preparationStyle,
            String customIngredientName,
            /* The recipe's own wording for the ingredient — lets an uncatalogued one become a named custom ingredient. */
            String name,
            /* Only meaningful when unit is "custom". */
            String customUnit,
            /* Only meaningful when preparationStyle is "custom". */
            String customPreparationStyle
    ) {
        public Ingredient(String ingredientId, Double quantity, String unit, String preparationStyle, String customIngredientName) {
            this(ingredientId, quantity, unit, preparationStyle, customIngredientName, null, null, null);
        }
    }
}
