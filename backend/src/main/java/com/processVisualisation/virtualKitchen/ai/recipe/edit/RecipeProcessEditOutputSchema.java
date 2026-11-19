package com.processVisualisation.virtualKitchen.ai.recipe.edit;

import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutputSchema;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutputSchema.Field;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutputSchema.arrayOf;
import static com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutputSchema.describe;
import static com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutputSchema.enumOf;
import static com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutputSchema.number;
import static com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutputSchema.object;
import static com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutputSchema.string;

/**
 * The output contract of an AI EDIT request (see {@link RecipeProcessEditOutput}): a summary plus a list of edit
 * operations, or a clarification question. As with {@link RecipeProcessOutputSchema}, one field list per operation
 * drives both {@link #jsonSchema()} and {@link #promptBlock()}; the STEP and INGREDIENT objects are the generation
 * schema's own, so an added step is described exactly like a generated one.
 */
@Component
public class RecipeProcessEditOutputSchema {

    public static final String SCHEMA_NAME = "recipe_process_edit";

    /** Hard cap on operations per edit — an instruction needing more is not a "small change". */
    public static final int MAX_OPERATIONS = 15;

    /** Step fields an UPDATE_STEP may reset via "clear" ("temperature" resets value and unit together). */
    public static final List<String> CLEARABLE_FIELDS = List.of(
            "customActionName", "expectedOutput", "temperature", "flameLevel", "duration", "repeatInterval", "fromSteps", "uses");

    private final Map<String, Object> jsonSchema;
    private final String promptBlock;

    public RecipeProcessEditOutputSchema(RecipeStepVocabularyProvider vocabulary, RecipeProcessOutputSchema generationSchema) {
        Map<String, Object> ingredient = generationSchema.ingredientSchema();
        List<Field> patchFields = List.of(
                new Field("action", string(), false),
                new Field("customActionName", string(), false),
                new Field("actionDescription", string(), false),
                new Field("expectedOutput", string(), false),
                new Field("temperatureValue", number(), false),
                new Field("temperatureUnit", enumOf(vocabulary.temperatureUnitIds()), false),
                new Field("flameLevel", enumOf(List.copyOf(vocabulary.flameLevelIdSet())), false),
                new Field("duration", string(), false),
                new Field("repeatInterval", string(), false),
                new Field("fromSteps", arrayOf(string()), false),
                new Field("title", string(), false),
                new Field("summary", string(), false),
                new Field("uses", arrayOf(string()), false)
        );
        Map<String, Object> expectedResult = enumOf(List.of("success", "failure"));

        List<OperationSpec> operations = List.of(
                new OperationSpec("ADD_STEP", List.of(
                        new Field("after", string(), true),
                        new Field("step", generationSchema.stepSchema(), true))),
                new OperationSpec("ADD_CONDITION", List.of(
                        new Field("after", string(), true),
                        new Field("stepId", string(), false),
                        new Field("title", string(), true),
                        new Field("actionDescription", string(), true),
                        new Field("expectedResult", expectedResult, false))),
                new OperationSpec("UPDATE_STEP", List.of(
                        new Field("target", string(), true),
                        new Field("set", object(patchFields), false),
                        new Field("clear", arrayOf(enumOf(CLEARABLE_FIELDS)), false))),
                new OperationSpec("UPDATE_CONDITION", List.of(
                        new Field("target", string(), true),
                        new Field("title", string(), false),
                        new Field("actionDescription", string(), false),
                        new Field("expectedResult", expectedResult, false))),
                new OperationSpec("ADD_INGREDIENT", List.of(
                        new Field("target", string(), true),
                        new Field("ingredient", ingredient, true))),
                new OperationSpec("UPDATE_INGREDIENT", List.of(
                        new Field("target", string(), true),
                        new Field("ingredient", ingredient, true))),
                new OperationSpec("REMOVE_INGREDIENT", List.of(
                        new Field("target", string(), true),
                        new Field("ingredientId", string(), true))),
                new OperationSpec("REPLACE_INGREDIENT", List.of(
                        new Field("target", string(), false),
                        new Field("from", string(), true),
                        new Field("ingredient", ingredient, true))),
                new OperationSpec("DELETE_NODE", List.of(
                        new Field("target", string(), true))),
                new OperationSpec("MOVE_NODE", List.of(
                        new Field("target", string(), true),
                        new Field("after", string(), true)))
        );

        Map<String, Object> operation = Map.of("anyOf", operations.stream().map(OperationSpec::schema).toList());
        this.jsonSchema = object(List.of(
                new Field("summary", string(), true),
                new Field("operations", arrayOf(operation), true),
                new Field("clarification", string(), false)
        ));
        this.promptBlock = """
                OUTPUT
                Return ONE minified JSON object on a single line (no indentation, no line breaks).
                OMIT every optional field that is empty, null, an empty list, or unchanged — never write "", null or [].
                "!" marks a required field; everything else is optional.
                ROOT {summary!, operations!, clarification}
                OPERATIONS (each has "op" set to its name):
                %s
                SET (UPDATE_STEP) {%s}
                clear values: %s
                STEP and INGREDIENT objects are exactly as described under STEP FIELD RULES and VOCABULARY.
                """.formatted(
                operations.stream().map(spec -> "- " + spec.name() + " {op!, " + describe(spec.fields()) + "}")
                        .collect(Collectors.joining("\n")),
                describe(patchFields),
                String.join(", ", CLEARABLE_FIELDS)
        );
    }

    /** JSON Schema for the edit response (same draft subset as {@link RecipeProcessOutputSchema#jsonSchema()}). */
    public Map<String, Object> jsonSchema() {
        return jsonSchema;
    }

    /** The OUTPUT section of the edit system prompt, rendered from the same field lists as {@link #jsonSchema()}. */
    public String promptBlock() {
        return promptBlock;
    }

    private record OperationSpec(String name, List<Field> fields) {
        Map<String, Object> schema() {
            List<Field> all = new java.util.ArrayList<>();
            all.add(new Field("op", enumOf(List.of(name)), true));
            all.addAll(fields);
            return object(all);
        }
    }
}
