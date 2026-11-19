package com.processVisualisation.virtualKitchen.ai.recipe.edit;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutput;

import java.util.List;

/**
 * The compact JSON the model returns for an EDIT request, exactly as described by {@link RecipeProcessEditOutputSchema}.
 * One flat {@link Operation} record carries every operation kind; which fields apply depends on {@code op}.
 * Node references are the aliases from the prompt ("s3"), the stepId of a node added earlier in the same list, or "START".
 */
public record RecipeProcessEditOutput(String summary, List<Operation> operations, String clarification) {

    public record Operation(
            String op,
            String target,
            String after,
            /* ADD_STEP: the new step (its optional stepId names it for later operations). */
            RecipeProcessOutput.Node step,
            /* ADD_CONDITION / UPDATE_CONDITION. */
            String stepId,
            String title,
            String actionDescription,
            String expectedResult,
            /* UPDATE_STEP. */
            StepPatch set,
            List<String> clear,
            /* Ingredient operations: the ingredient to add, the new values to merge, or the replacement. */
            RecipeProcessOutput.Ingredient ingredient,
            /* REMOVE_INGREDIENT: the ingredient to drop. */
            String ingredientId,
            /* REPLACE_INGREDIENT: the ingredient being replaced. */
            String from
    ) {}

    /** The fields of a STEP an UPDATE_STEP may overwrite; absent = unchanged. */
    public record StepPatch(
            String action,
            String customActionName,
            String actionDescription,
            String expectedOutput,
            Double temperatureValue,
            String temperatureUnit,
            String flameLevel,
            String duration,
            String repeatInterval,
            List<String> fromSteps,
            String title,
            String summary,
            /* The process aliases (p1, ...) whose output the step uses. */
            @JsonAlias("processes") List<String> uses
    ) {}
}
