package com.processVisualisation.virtualKitchen.ai.recipe.edit;

import com.processVisualisation.virtualKitchen.ai.recipe.edit.dto.EditSubprocessRefDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.edit.dto.EditTargetNodeDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.edit.dto.EditTargetProcessDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutput;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedActionOnDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedActionOnIngredientDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedRecipeStepDTO;

import java.util.ArrayList;
import java.util.List;

/** A four-step pasta process (boil water, add pasta, cook, drain) as the frontend would send it for an EDIT. */
final class EditTestFixtures {

    static final String BOIL_ID = "3f1c9a2e-0000-4000-8000-000000000001";
    static final String ADD_ID = "3f1c9a2e-0000-4000-8000-000000000002";
    static final String COOK_ID = "3f1c9a2e-0000-4000-8000-000000000003";
    static final String DRAIN_ID = "3f1c9a2e-0000-4000-8000-000000000004";
    static final long PROCESS_ID = 11L;
    static final long SAUCE_PROCESS_ID = 12L;

    private EditTestFixtures() {}

    static EditTargetProcessDTO pastaProcess() {
        List<EditTargetNodeDTO> nodes = new ArrayList<>();
        nodes.add(node(BOIL_ID, 1, step("boil", List.of(ingredient("water", 2.0, "l")), List.of(),
                "Bring the water to a boil", "boiling water", "high", null), List.of(ADD_ID)));
        nodes.add(node(ADD_ID, 2, step("add", List.of(ingredient("pasta", 200.0, "g")), List.of(),
                "Add the pasta to the water", "pasta in water", null, null), List.of(COOK_ID)));
        nodes.add(node(COOK_ID, 3, step("boil", List.of(), List.of(ADD_ID),
                "Cook the pasta for 10 minutes", "cooked pasta", "high", "10 minutes"), List.of(DRAIN_ID)));
        nodes.add(node(DRAIN_ID, 4, step("drain", List.of(), List.of(COOK_ID),
                "Drain the pasta", "drained pasta", null, null), List.of()));
        return new EditTargetProcessDTO(PROCESS_ID, "Main Process", nodes,
                List.of(new EditSubprocessRefDTO(SAUCE_PROCESS_ID, "Prepare Sauce")));
    }

    static RecipeProcessEditContext pastaContext() {
        return RecipeProcessEditContext.of(pastaProcess(), null);
    }

    static EditTargetNodeDTO node(String id, Integer stepNumber, GeneratedRecipeStepDTO content, List<String> next) {
        EditTargetNodeDTO node = new EditTargetNodeDTO();
        node.setNodeId(id);
        node.setStepNumber(stepNumber);
        node.setContent(content);
        node.setNextNodeIds(next);
        return node;
    }

    static GeneratedRecipeStepDTO step(
            String action, List<GeneratedActionOnIngredientDTO> ingredients, List<String> fromNodeIds,
            String description, String expectedOutput, String flameLevel, String duration
    ) {
        GeneratedRecipeStepDTO step = new GeneratedRecipeStepDTO();
        step.setNodeType("STEP");
        step.setAction(action);
        step.setActionOn(new GeneratedActionOnDTO(ingredients, List.of(), fromNodeIds));
        step.setActionDescription(description);
        step.setExpectedOutput(expectedOutput);
        step.setFlameLevel(flameLevel);
        step.setDuration(duration);
        return step;
    }

    static GeneratedActionOnIngredientDTO ingredient(String id, Double quantity, String unit) {
        return new GeneratedActionOnIngredientDTO(id, quantity, unit, null, null);
    }

    static RecipeProcessOutput.Node newStep(String action, List<RecipeProcessOutput.Ingredient> ingredients, String description) {
        return new RecipeProcessOutput.Node(null, null, action, null, ingredients, null, null, description, null,
                null, null, null, null, null, null, null, null);
    }

    static RecipeProcessOutput.Ingredient outIngredient(String id, Double quantity, String unit) {
        return new RecipeProcessOutput.Ingredient(id, quantity, unit, null, null);
    }

    /** An operation with only the given fields set. */
    static OperationBuilder op(String op) {
        return new OperationBuilder(op);
    }

    static final class OperationBuilder {
        private final String op;
        private String target;
        private String after;
        private RecipeProcessOutput.Node step;
        private String stepId;
        private String title;
        private String actionDescription;
        private RecipeProcessEditOutput.StepPatch patch;
        private List<String> clear;
        private RecipeProcessOutput.Ingredient ingredient;
        private String ingredientId;
        private String from;

        private OperationBuilder(String op) {
            this.op = op;
        }

        OperationBuilder target(String value) { this.target = value; return this; }
        OperationBuilder after(String value) { this.after = value; return this; }
        OperationBuilder step(RecipeProcessOutput.Node value) { this.step = value; return this; }
        OperationBuilder stepId(String value) { this.stepId = value; return this; }
        OperationBuilder title(String value) { this.title = value; return this; }
        OperationBuilder actionDescription(String value) { this.actionDescription = value; return this; }
        OperationBuilder set(RecipeProcessEditOutput.StepPatch value) { this.patch = value; return this; }
        OperationBuilder clear(List<String> value) { this.clear = value; return this; }
        OperationBuilder ingredient(RecipeProcessOutput.Ingredient value) { this.ingredient = value; return this; }
        OperationBuilder ingredientId(String value) { this.ingredientId = value; return this; }
        OperationBuilder from(String value) { this.from = value; return this; }

        RecipeProcessEditOutput.Operation build() {
            return new RecipeProcessEditOutput.Operation(op, target, after, step, stepId, title, actionDescription, null,
                    patch, clear, ingredient, ingredientId, from);
        }
    }

    static RecipeProcessEditOutput.StepPatch patchDuration(String duration) {
        return new RecipeProcessEditOutput.StepPatch(null, null, null, null, null, null, null, duration, null, null, null, null, null);
    }

    static RecipeProcessEditOutput.StepPatch patchUses(List<String> uses) {
        return new RecipeProcessEditOutput.StepPatch(null, null, null, null, null, null, null, null, null, null, null, null, uses);
    }

    static RecipeProcessEditOutput.StepPatch patchFlame(String flameLevel) {
        return new RecipeProcessEditOutput.StepPatch(null, null, null, null, null, null, flameLevel, null, null, null, null, null, null);
    }

    static RecipeProcessEditOutput output(RecipeProcessEditOutput.Operation... operations) {
        return new RecipeProcessEditOutput("Changed the recipe.", List.of(operations), null);
    }
}
