package com.processVisualisation.virtualKitchen.ai.recipe.generation;

import com.processVisualisation.virtualKitchen.ai.recipe.edit.RecipeProcessEditOutputSchema;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The output contract of recipe text -> Process generation: what the model must return (see
 * {@link RecipeProcessOutput}) — named preparation flows plus the one main cooking sequence. One field list per object drives both {@link #jsonSchema()} — sent to providers
 * that support constrained/structured output — and {@link #promptBlock()}, the OUTPUT section of the system
 * prompt, so the two can never drift apart.
 * <p>
 * The wire shape is deliberately compact: every optional field is omitted when empty/default (nodeType defaults
 * to STEP, expectedResult to success, expectedOutput to ""), and {@code actionOn} is flattened onto the step.
 * {@link RecipeProcessOutputNormalizer} turns it back into the {@code Generated*DTO}s the frontend consumes.
 * Catalog ids with large vocabularies (actions, ingredients, preparation styles) are not enumerated here to keep
 * the decoding grammar small — {@link RecipeProcessGenerationValidator} checks them instead.
 */
@Component
public class RecipeProcessOutputSchema {

    public static final String SCHEMA_NAME = "recipe_process";

    public record Field(String name, Map<String, Object> type, boolean required) {}

    private final Map<String, Object> jsonSchema;
    private final String promptBlock;
    private final Map<String, Object> stepSchema;
    private final Map<String, Object> ingredientSchema;

    public RecipeProcessOutputSchema(RecipeStepVocabularyProvider vocabulary) {
        List<String> unitIds = vocabulary.units().stream().map(RecipeStepVocabularyProvider.UnitDefinition::id).toList();

        List<Field> ingredientFields = List.of(
                new Field("ingredientId", string(), true),
                new Field("name", string(), true),
                new Field("quantity", number(), false),
                new Field("unit", enumOf(unitIds), false),
                new Field("customUnit", string(), false),
                new Field("preparationStyle", string(), false),
                new Field("customPreparationStyle", string(), false),
                new Field("customIngredientName", string(), false)
        );
        List<Field> stepFields = List.of(
                new Field("nodeType", enumOf(List.of("STEP")), false),
                new Field("stepId", string(), false),
                new Field("action", string(), true),
                new Field("customActionName", string(), false),
                new Field("title", string(), false),
                new Field("ingredients", arrayOf(object(ingredientFields)), false),
                new Field("uses", arrayOf(string()), false),
                new Field("fromSteps", arrayOf(string()), false),
                new Field("actionDescription", string(), true),
                new Field("expectedOutput", string(), false),
                new Field("summary", string(), true),
                new Field("temperatureValue", number(), false),
                new Field("temperatureUnit", enumOf(vocabulary.temperatureUnitIds()), false),
                new Field("flameLevel", enumOf(List.copyOf(vocabulary.flameLevelIdSet())), false),
                new Field("duration", string(), false),
                new Field("repeatInterval", string(), false)
        );
        List<Field> conditionFields = List.of(
                new Field("nodeType", enumOf(List.of("CONDITION")), true),
                new Field("title", string(), true),
                new Field("expectedResult", enumOf(List.of("success", "failure")), false),
                new Field("actionDescription", string(), true),
                new Field("expectedOutput", string(), false)
        );
        this.stepSchema = object(stepFields);
        this.ingredientSchema = object(ingredientFields);
        Map<String, Object> node = Map.of("anyOf", List.of(stepSchema, object(conditionFields)));
        List<Field> preparationFields = List.of(
                new Field("ref", string(), true),
                new Field("output", string(), true),
                new Field("steps", arrayOf(node), true)
        );
        List<Field> mainFields = List.of(
                new Field("name", string(), true),
                new Field("steps", arrayOf(node), true)
        );

        // "preparations" comes first so the model writes the prepared components before the main sequence that uses them.
        this.jsonSchema = object(List.of(
                new Field("preparations", arrayOf(object(preparationFields)), false),
                new Field("main", object(mainFields), true)
        ));
        this.promptBlock = """
                OUTPUT
                Return ONE minified JSON object on a single line (no indentation, no line breaks).
                OMIT every optional field that is empty, null, an empty list, or its default — never write "", null or [].
                "!" marks a required field; everything else is optional.
                ROOT {%s}
                PREPARATION {%s}
                MAIN {%s}
                STEP {%s}   (nodeType defaults to STEP — omit it)
                CONDITION {%s}   (expectedResult defaults to success)
                INGREDIENT {%s}

                Example STEP:      {"action":"saute","uses":["chopped_onions"],"ingredients":[{"ingredientId":"garlic","name":"garlic","quantity":2,"unit":"clove","preparationStyle":"minced"}],"actionDescription":"Saute the onions and garlic","expectedOutput":"golden onions","title":"Saute onions and garlic","summary":"Tip the chopped onions into the hot pan with the minced garlic and keep them moving until they turn golden and smell sweet.","flameLevel":"medium","duration":"5 minutes"}
                Example CONDITION: {"nodeType":"CONDITION","title":"Is the oil hot?","actionDescription":"Drop in a pinch of batter; it should rise at once"}
                """.formatted(
                describe(List.of(new Field("preparations", Map.of(), false), new Field("main", Map.of(), true))),
                describe(preparationFields),
                describe(mainFields),
                describe(stepFields.stream().filter(field -> !field.name().equals("nodeType")).toList()),
                describe(conditionFields),
                describe(ingredientFields)
        );
    }

    /** JSON Schema (draft 2020-12 subset understood by Ollama, OpenAI and Gemini) for the generation response. */
    public Map<String, Object> jsonSchema() {
        return jsonSchema;
    }

    /** The OUTPUT section of the system prompt, rendered from the same field lists as {@link #jsonSchema()}. */
    public String promptBlock() {
        return promptBlock;
    }

    /** The STEP object schema — reused by {@link RecipeProcessEditOutputSchema} for a step added while editing. */
    public Map<String, Object> stepSchema() {
        return stepSchema;
    }

    /** The INGREDIENT object schema, reused by {@link RecipeProcessEditOutputSchema}. */
    public Map<String, Object> ingredientSchema() {
        return ingredientSchema;
    }

    public static String describe(List<Field> fields) {
        return fields.stream().map(field -> field.required() ? field.name() + "!" : field.name()).collect(Collectors.joining(", "));
    }

    public static Map<String, Object> object(List<Field> fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Field field : fields) {
            properties.put(field.name(), field.type());
            if (field.required()) required.add(field.name());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    public static Map<String, Object> arrayOf(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    public static Map<String, Object> string() {
        return Map.of("type", "string");
    }

    public static Map<String, Object> number() {
        return Map.of("type", "number");
    }

    public static Map<String, Object> enumOf(List<String> values) {
        return Map.of("type", "string", "enum", values);
    }
}
