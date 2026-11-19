package com.processVisualisation.virtualKitchen.ai.recipe.generation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedRecipeProcessDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedRecipeStepDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider;
import com.processVisualisation.virtualKitchen.recipe.validation.ProcessValidationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compact output contract end to end: schema shape, parsing the model's minimal JSON, normalizing the omitted
 * defaults back into the {@code Generated*DTO}s, and passing validation.
 */
class RecipeProcessOutputTest {

    private static final RecipeStepVocabularyProvider VOCABULARY = new RecipeStepVocabularyProvider();

    private final RecipeProcessOutputSchema schema = new RecipeProcessOutputSchema(VOCABULARY);
    private final RecipeProcessOutputNormalizer normalizer = new RecipeProcessOutputNormalizer();
    private final RecipeProcessGenerationValidator validator = new RecipeProcessGenerationValidator(VOCABULARY);
    private final ObjectMapper objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void schemaRequiresOnlyTheContractFields() {
        JsonNode root = objectMapper.valueToTree(schema.jsonSchema());
        assertEquals(List.of("main"), required(root));

        JsonNode preparation = root.at("/properties/preparations/items");
        assertEquals(List.of("ref", "output", "steps"), required(preparation));

        JsonNode nodeVariants = root.at("/properties/main/properties/steps/items/anyOf");
        assertEquals(List.of("action", "actionDescription", "summary"), required(nodeVariants.get(0)));
        assertEquals(List.of("nodeType", "title", "actionDescription"), required(nodeVariants.get(1)));
        assertEquals(List.of("ingredientId", "name"), required(nodeVariants.get(0).at("/properties/ingredients/items")));
        assertTrue(nodeVariants.get(0).at("/properties/uses").isObject(), "a step names the preparations it uses");
        assertTrue(nodeVariants.get(0).at("/properties/flameLevel/enum").toString().contains("medium-high"));
        assertTrue(nodeVariants.get(0).at("/properties/unit").isMissingNode(), "unit lives on the ingredient, not the step");
    }

    @Test
    void minimalOutputIsNormalizedWithDefaultsAndPassesValidation() throws Exception {
        String content = """
                {"mainProcess":{"name":"Egg Fry","steps":[
                {"stepId":"s1","action":"boil","ingredients":[{"ingredientId":"egg","quantity":4,"unit":"piece"}],"actionDescription":"Boil the eggs","expectedOutput":"boiled eggs"},
                {"action":"wait","actionDescription":"Let them rest"},
                {"nodeType":"CONDITION","title":"Are the eggs firm?","actionDescription":"Check the yolk"},
                {"action":"fry","ingredients":[{"ingredientId":"onion","quantity":1,"unit":"piece"}],"fromSteps":["s1"],"actionDescription":"Fry eggs with onion","unexpectedKey":true}
                ]}}""";

        RecipeProcessOutputNormalizer.Normalized normalized = normalizer.normalize(objectMapper.readValue(content, RecipeProcessOutput.class));
        GeneratedRecipeProcessDTO main = normalized.main();
        List<GeneratedRecipeProcessDTO> subprocesses = normalized.preparations();

        assertEquals(List.of(), subprocesses, "omitted preparations become an empty list");
        GeneratedRecipeStepDTO rest = main.getSteps().get(1);
        assertEquals("STEP", rest.getNodeType());
        assertEquals("", rest.getExpectedOutput());
        assertEquals(List.of(), rest.getActionOn().getIngredients());
        GeneratedRecipeStepDTO condition = main.getSteps().get(2);
        assertEquals("success", condition.getExpectedResult());
        assertEquals(List.of("s1"), main.getSteps().get(3).getActionOn().getSteps(), "fromSteps maps onto actionOn.steps");

        ProcessValidationResult result = validator.validate(main, subprocesses);
        assertTrue(result.isValid(), () -> String.join("; ", result.getErrors()));
    }

    @Test
    void generatedStepIdsNeverCollideWithTheModelsOwn() throws Exception {
        // The model named the third step "s1"; the first step's generated id must not reuse it.
        String content = """
                {"mainProcess":{"name":"R","steps":[
                {"action":"stir","actionDescription":"a"},
                {"action":"stir","actionDescription":"b"},
                {"stepId":"s1","action":"stir","actionDescription":"c","expectedOutput":"x"}
                ]}}""";

        GeneratedRecipeProcessDTO main = normalizer.normalize(objectMapper.readValue(content, RecipeProcessOutput.class)).main();

        List<String> ids = main.getSteps().stream().map(GeneratedRecipeStepDTO::getStepId).toList();
        assertEquals(3, ids.stream().distinct().count(), () -> "stepIds must be unique, got " + ids);
        assertEquals("s1", ids.get(2));
    }

    @Test
    void validationErrorsUseTheFlattenedWireNames() throws Exception {
        String content = """
                {"mainProcess":{"name":"R","steps":[
                {"action":"chop","fromSteps":["nope"],"actionDescription":"Chop"}
                ]}}""";

        GeneratedRecipeProcessDTO main = normalizer.normalize(objectMapper.readValue(content, RecipeProcessOutput.class)).main();
        List<String> errors = validator.validate(main, List.of()).getErrors();

        assertTrue(errors.stream().anyMatch(e -> e.startsWith("main.steps[0].fromSteps")), () -> errors.toString());
        assertTrue(errors.stream().noneMatch(e -> e.contains("actionOn")), () -> errors.toString());
    }

    private static List<String> required(JsonNode objectSchema) {
        List<String> names = new ArrayList<>();
        objectSchema.path("required").forEach(name -> names.add(name.asText()));
        return names;
    }
}
