package com.processVisualisation.virtualKitchen.ai.recipe.edit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.recipe.edit.dto.EditTargetNodeDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessGenerationPromptBuilder;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedActionOnIngredientDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedRecipeStepDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the prompts for an AI EDIT request: the model sees the current process (aliased by
 * {@link RecipeProcessEditContext}) plus the user's instruction, and answers with the smallest list of edit
 * operations described by {@link RecipeProcessEditOutputSchema}. The STEP field rules and vocabulary are the
 * generation prompt's own ({@link RecipeProcessGenerationPromptBuilder#stepAuthoringRulesAndVocabulary()}), so an
 * added or changed step is held to exactly the same standard as a generated one.
 */
@Component
public class RecipeProcessEditPromptBuilder {

    /** Logged with every edit; bump it whenever the edit prompt wording or structure changes. */
    public static final String PROMPT_VERSION = "3";

    private final RecipeProcessGenerationPromptBuilder generationPromptBuilder;
    private final RecipeProcessEditOutputSchema outputSchema;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Built on first use and rebuilt when the vocabulary (the ingredient catalog) changes. */
    private volatile String systemPrompt;
    private volatile long systemPromptVersion = Long.MIN_VALUE;

    public RecipeProcessEditPromptBuilder(
            RecipeProcessGenerationPromptBuilder generationPromptBuilder, RecipeProcessEditOutputSchema outputSchema
    ) {
        this.generationPromptBuilder = generationPromptBuilder;
        this.outputSchema = outputSchema;
    }

    public String buildSystemPrompt() {
        long version = generationPromptBuilder.vocabularyVersion();
        if (systemPrompt == null || systemPromptVersion != version) {
            systemPrompt = renderSystemPrompt();
            systemPromptVersion = version;
        }
        return systemPrompt;
    }

    private String renderSystemPrompt() {
        return """
                You edit an existing recipe process. The user gives an INSTRUCTION; return the SMALLEST set of edit
                operations that carries it out on CURRENT PROCESS. You are changing a recipe the user already built,
                not writing a new one.

                Return ONLY valid JSON.
                Do not return markdown, explanations, comments, or code fences.
                Never output node UUIDs, edges, positions or any other UI field: the application rewires and lays out
                the graph itself.

                """ + outputSchema.promptBlock() + """

                CURRENT PROCESS FORMAT
                - One node per line, in reading order. "id" (s1, s2, ...) is how operations refer to that node.
                - "step" is the number the editor shows for a STEP ("step 3" in the instruction means that node).
                  CONDITION nodes are checks and have no step number.
                - "next" lists the node(s) a node leads to. A CONDITION has "yes" (continue) and "no" (usually back to
                  the step it checks) instead.
                - PREPARED COMPONENTS lists what the recipe's Preparation makes (each flow's named output, and
                  intermediate results) as p1, p2, ...; a main step's "uses" lists these ids.
                - @[words](ingredient:...), @[words](prepared:p1) and @[words](step:s2) inside an actionDescription mark what
                  that step uses (see ANNOTATIONS below). Keep them when you reword a description, and annotate what a
                  step you add or change uses.
                - A Preparation holds independent flows (separate connected groups of steps). Its steps never "use"
                  anything. A flow's last step names its output in "expectedOutput". To start a new flow, ADD_STEP
                  with "after":"NEW_FLOW" (then add its next steps after that one).

                MINIMAL CHANGES
                - Change only what the instruction asks for. Never restate, re-create, reorder or reword a node the
                  instruction does not concern, and never regenerate the recipe.
                - Prefer the narrowest operation: UPDATE_STEP or an ingredient operation over DELETE_NODE + ADD_STEP,
                  UPDATE_INGREDIENT over REMOVE_INGREDIENT + ADD_INGREDIENT, MOVE_NODE over DELETE_NODE + ADD_STEP.
                - UPDATE_STEP "set" holds only the fields that change; every field you leave out stays as it is.
                  "fromSteps" and "uses" in "set" replace the whole list. Use "clear" to remove a value.
                - When the change makes another field of the SAME node wrong (e.g. a new duration contradicts its
                  actionDescription), fix that field in the same UPDATE_STEP. Leave other nodes alone unless the
                  instruction says otherwise.

                REFERRING TO NODES
                - "target" and "after" are ids from CURRENT PROCESS, or the stepId you gave a node added by an
                  EARLIER operation in this same list (e.g. "n1"). "after":"START" inserts before the first node; "after":"NEW_FLOW" (ADD_STEP only) starts a new, unconnected flow.
                - Operations are applied in order: refer to nodes as they exist at that point, and never refer to a
                  node after deleting it.
                - "after X" / "once X is done" -> after X. "before X" -> after the node that comes right before X
                  ("START" if X is first). "between step 3 and step 4" -> after step 3. "at the end" -> after the
                  last node. "the last step" -> the last STEP.
                - "this step", "the selected step" and "the previous step" mean SELECTED STEP when one is given.
                - Identify a step by what it does ("the onion step", "the tomato-cutting step"). If two or more
                  steps match equally well, ask instead of guessing.

                ADDING NODES
                - ADD_STEP adds a complete STEP that follows the STEP FIELD RULES below. Give it a "stepId" ("n1",
                  "n2", ...) only when a later operation in this list refers to it.
                - The new step is connected between "after" and the node that used to follow it.
                - ADD_CONDITION adds a yes/no check right after the STEP it checks: "Yes" continues to the next node,
                  "No" goes back to that step until the check passes. Phrase "title" so "Yes" means done.
                - A waiting/resting period ("rest 5 minutes", "let it cool") is its own STEP with action "rest",
                  "wait", "cool" or "chill" and a duration.

                INGREDIENTS
                - UPDATE_INGREDIENT changes an ingredient the step already has: give its ingredientId plus only the
                  fields that change (quantity, unit, preparationStyle).
                - REPLACE_INGREDIENT swaps one ingredient for another ("butter instead of olive oil"). Omit "target"
                  to replace it in every step that uses it. Omit quantity and unit to keep the old amount.
                - ADD_INGREDIENT / REMOVE_INGREDIENT add or drop an ingredient on an existing step without
                  touching the step's other fields.

                WHEN TO ASK INSTEAD (clarification)
                - Return {"summary":"...","operations":[],"clarification":"<one short question>"} when:
                  the step meant is ambiguous; a value the change needs is not given (e.g. "the quantity is wrong"
                  without the correct amount, and the selected step does not make it obvious); or the instruction
                  describes a whole new or different recipe rather than a change to this one (then say that
                  generating a new flow would suit it better).
                - Do not invent quantities, timings, temperatures or ingredients the instruction does not state.

                SUMMARY
                - "summary" is one short sentence for the user describing what you changed (or why you are
                  asking), e.g. "Added a step to season the pasta with salt before draining."

                EXAMPLES (CURRENT PROCESS: s1 boil water, s2 add pasta, s3 cook pasta 10 minutes on high, s4 drain)
                - "Season the pasta with salt before draining" ->
                  {"summary":"Added a step to season the pasta with salt before draining.","operations":[{"op":"ADD_STEP","after":"s3","step":{"action":"season","ingredients":[{"ingredientId":"salt","unit":"to-taste"}],"actionDescription":"Season the pasta with salt"}}]}
                - "Cook it for 8 minutes instead of 10, on medium" ->
                  {"summary":"Changed the cooking time to 8 minutes on medium heat.","operations":[{"op":"UPDATE_STEP","target":"s3","set":{"duration":"8 minutes","flameLevel":"medium"}}]}
                - "Remove the last step" -> {"summary":"Removed the draining step.","operations":[{"op":"DELETE_NODE","target":"s4"}]}
                - "Use butter instead of olive oil" ->
                  {"summary":"Replaced olive oil with butter.","operations":[{"op":"REPLACE_INGREDIENT","from":"olive-oil","ingredient":{"ingredientId":"butter"}}]}
                - "Garnish with coriander at the end" ->
                  {"summary":"Added a final garnish of coriander.","operations":[{"op":"ADD_STEP","after":"s4","step":{"action":"garnish","ingredients":[{"ingredientId":"cilantro","unit":"as-needed"}],"actionDescription":"Garnish with chopped coriander"}}]}

                STEP FIELD RULES (for every step you add or change)
                """ + generationPromptBuilder.stepAuthoringRulesAndVocabulary() + """

                GENERAL RULES
                - Output catalog ids exactly as listed. Aliases in [brackets] exist only to help you read the
                  instruction; never output an alias, a label, or an id that is not listed.
                - Keep every value of CURRENT PROCESS that the instruction does not change exactly as it is.
                - Return ONLY minified JSON matching the OUTPUT shape, with every empty/default field omitted.
                """;
    }

    String buildInitialPrompt(RecipeProcessEditContext context, String instruction) {
        return renderProcess(context)
                + "\n\nINSTRUCTION:\n" + instruction
                + "\n\nReturn the JSON edit operations only.";
    }

    String buildRetryPrompt(RecipeProcessEditContext context, String instruction, String previousOutput, List<String> validationErrors) {
        return renderProcess(context)
                + "\n\nINSTRUCTION:\n" + instruction
                + "\n\nYOUR PREVIOUS OUTPUT:\n"
                + (previousOutput == null || previousOutput.isBlank() ? "(empty)" : previousOutput)
                + "\n\nVALIDATION ERRORS IN THAT OUTPUT:\n"
                + String.join("; ", validationErrors)
                + "\n\nFix only these errors and return the complete corrected JSON only.";
    }

    private String renderProcess(RecipeProcessEditContext context) {
        StringBuilder out = new StringBuilder("CURRENT PROCESS");
        if (context.processName() != null && !context.processName().isBlank()) {
            out.append(" \"").append(context.processName()).append('"');
        }
        out.append(":\n");
        if (context.steps().isEmpty()) out.append("(no nodes yet)\n");
        for (GeneratedRecipeStepDTO step : context.steps()) {
            out.append(toJson(describeNode(step, context.node(step.getStepId()), context))).append('\n');
        }

        if (!context.processNameByAlias().isEmpty()) {
            out.append("\nPREPARED COMPONENTS:\n");
            context.processNameByAlias().forEach((alias, name) ->
                    out.append("- ").append(alias).append(": ").append(name == null || name.isBlank() ? "(unnamed)" : name).append('\n'));
        }

        out.append("\nSELECTED STEP: ").append(context.selectedAlias() == null ? "none" : context.selectedAlias());
        return out.toString();
    }

    private Map<String, Object> describeNode(GeneratedRecipeStepDTO step, EditTargetNodeDTO source, RecipeProcessEditContext context) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", step.getStepId());
        boolean condition = "CONDITION".equals(step.getNodeType());
        if (condition) {
            node.put("nodeType", "CONDITION");
            node.put("title", step.getTitle());
            if ("failure".equals(step.getExpectedResult())) node.put("expectedResult", "failure");
            node.put("actionDescription", step.getActionDescription());
            node.put("yes", context.aliasOfNodeId(source.getYesNodeId()));
            node.put("no", context.aliasOfNodeId(source.getNoNodeId()));
            return node;
        }

        node.put("step", source.getStepNumber());
        node.put("action", step.getAction());
        node.put("customActionName", step.getCustomActionName());
        List<Map<String, Object>> ingredients = new ArrayList<>();
        for (GeneratedActionOnIngredientDTO ingredient : step.getActionOn().getIngredients()) {
            if (ingredient == null) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ingredientId", ingredient.getIngredientId());
            entry.put("quantity", ingredient.getQuantity());
            entry.put("unit", ingredient.getUnit());
            entry.put("preparationStyle", ingredient.getPreparationStyle());
            entry.put("customIngredientName", ingredient.getCustomIngredientName());
            ingredients.add(withoutEmpty(entry));
        }
        node.put("ingredients", ingredients);
        node.put("uses", step.getActionOn().getProcesses());
        node.put("fromSteps", step.getActionOn().getSteps());
        node.put("actionDescription", step.getActionDescription());
        node.put("expectedOutput", step.getExpectedOutput());
        node.put("title", step.getTitle());
        node.put("summary", step.getSummary());
        node.put("temperatureValue", step.getTemperatureValue());
        node.put("temperatureUnit", step.getTemperatureUnit());
        if (step.getTemperatureValue() == null) node.put("temperature", step.getTemperature());
        node.put("flameLevel", step.getFlameLevel());
        node.put("duration", step.getDuration());
        node.put("repeatInterval", step.getRepeatInterval());
        node.put("next", source.getNextNodeIds() == null ? List.of()
                : source.getNextNodeIds().stream().map(context::aliasOfNodeId).filter(Objects::nonNull).toList());
        return node;
    }

    /** Drops null, blank and empty values, so each node line carries only what the node actually has. */
    private static Map<String, Object> withoutEmpty(Map<String, Object> values) {
        values.values().removeIf(value -> value == null
                || (value instanceof String text && text.isBlank())
                || (value instanceof java.util.Collection<?> collection && collection.isEmpty()));
        return values;
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(withoutEmpty(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not render the process for the edit prompt", e);
        }
    }
}
