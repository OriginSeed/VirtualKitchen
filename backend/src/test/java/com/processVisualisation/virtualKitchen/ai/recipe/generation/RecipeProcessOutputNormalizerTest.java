package com.processVisualisation.virtualKitchen.ai.recipe.generation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedActionOnIngredientDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedRecipeProcessDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedRecipeStepDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.IngredientDefinition;
import com.processVisualisation.virtualKitchen.recipe.validation.ProcessValidationResult;
import com.processVisualisation.virtualKitchen.store.catalog.StepCatalogResource;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deterministic repairs {@link RecipeProcessOutputNormalizer} applies before validation, so an understandable
 * recipe never fails on catalog detail — while structural mistakes are still left for the validator.
 */
class RecipeProcessOutputNormalizerTest {

    private static final RecipeStepVocabularyProvider SEED_VOCABULARY = new RecipeStepVocabularyProvider();
    /** The curry fixtures' database ingredient ids, mapped onto seed ingredients. */
    private static final RecipeStepVocabularyProvider DATABASE_VOCABULARY = vocabularyWithDatabaseIds(Map.ofEntries(
            Map.entry("308", "chicken"), Map.entry("19", "oil"), Map.entry("107", "onion"), Map.entry("112", "garlic"),
            Map.entry("113", "ginger"), Map.entry("82", "curry-powder"), Map.entry("41", "cumin-powder"),
            Map.entry("45", "turmeric"), Map.entry("44", "coriander-powder"), Map.entry("50", "cayenne"),
            Map.entry("4", "water"), Map.entry("400", "canned-tomatoes"), Map.entry("286", "yogurt"),
            Map.entry("92", "cilantro"), Map.entry("33", "salt"), Map.entry("80", "garam-masala"), Map.entry("8", "lemon-juice")));
    private final ObjectMapper objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RecipeProcessOutputNormalizer normalizer = new RecipeProcessOutputNormalizer(SEED_VOCABULARY);
    private final RecipeProcessGenerationValidator validator = new RecipeProcessGenerationValidator(SEED_VOCABULARY);

    // --- a real response that used to fail validation six times over ---

    /**
     * A chicken-curry answer from the pre-v3 prompt, rejected for: a compound preparation style, a style on an
     * action that takes none, two target-less steps, and two step-output references to a step without an
     * expectedOutput. Its ingredient ids are database ids, so the vocabulary maps them onto seed ingredients.
     */
    @Test
    void previouslyFailingChickenCurryResponse_isRepairedIntoAValidResult() throws Exception {
        RecipeStepVocabularyProvider vocabulary = DATABASE_VOCABULARY;
        RecipeProcessOutputNormalizer databaseNormalizer = new RecipeProcessOutputNormalizer(vocabulary);
        RecipeProcessGenerationValidator databaseValidator = new RecipeProcessGenerationValidator(vocabulary);

        RecipeProcessOutput output;
        try (InputStream json = getClass().getResourceAsStream("/ai/recipe/generation/chicken-curry-v2-response.json")) {
            output = objectMapper.readValue(json, RecipeProcessOutput.class);
        }
        RecipeProcessOutputNormalizer.Normalized normalized = databaseNormalizer.normalize(output);

        // Without the repairs this same structure is rejected (the old behaviour).
        ProcessValidationResult result = databaseValidator.validate(normalized.main(), normalized.preparations());
        assertTrue(result.isValid(), () -> String.join("; ", result.getErrors()));

        List<GeneratedRecipeStepDTO> steps = normalized.main().getSteps();
        assertEquals(13, steps.size());
        GeneratedActionOnIngredientDTO chicken = steps.get(0).getActionOn().getIngredients().get(0);
        assertNull(chicken.getPreparationStyle(), "season takes no preparation style");
        assertEquals("skinless, boneless", chicken.getNotes(), "the dropped style is kept as an ingredient note");
        assertEquals("Heat 1 to 2 tablespoons oil in a large skillet over high heat", steps.get(1).getExpectedOutput(),
                "a step whose output is used later gets its description as its expected output");
        assertNull(steps.get(6).getActionOn().getIngredients().get(2).getPreparationStyle(), "mix takes no preparation style");
        assertFalse(normalized.repairs().isEmpty());
    }

    /**
     * A v4 answer that failed with "fromSteps[0] references an unknown stepId": the model named the step it meant by
     * its expected output ("browned chicken") and never gave that step a stepId.
     */
    @Test
    void fromStepsNamingAnEarlierStepsOutput_isLinkedToThatStep() throws Exception {
        RecipeProcessOutput output;
        try (InputStream json = getClass().getResourceAsStream("/ai/recipe/generation/chicken-curry-v4-response.json")) {
            output = objectMapper.readValue(json, RecipeProcessOutput.class);
        }
        RecipeProcessOutputNormalizer.Normalized normalized = new RecipeProcessOutputNormalizer(DATABASE_VOCABULARY).normalize(output);

        ProcessValidationResult result = new RecipeProcessGenerationValidator(DATABASE_VOCABULARY).validate(normalized.main(), normalized.preparations());
        assertTrue(result.isValid(), () -> String.join("; ", result.getErrors()));

        List<GeneratedRecipeStepDTO> steps = normalized.main().getSteps();
        // Two earlier steps output "browned chicken" (sear, then transfer): the nearest one is used.
        assertEquals(List.of(steps.get(3).getStepId()), steps.get(7).getActionOn().getSteps());
        assertTrue(normalized.repairs().stream().anyMatch(repair -> repair.contains("'browned chicken' is an expected output")), normalized.repairs()::toString);
    }

    @Test
    void fromStepsWrittenAsASlugOfTheOutput_isLinkedToo_butAnUnknownNameIsStillAnError() throws Exception {
        RecipeProcessOutputNormalizer.Normalized slug = normalize("""
                {"main":{"name":"R","steps":[
                  {"action":"boil","ingredients":[{"ingredientId":"egg","name":"eggs","quantity":4,"unit":"piece"}],"actionDescription":"Boil","expectedOutput":"Boiled Eggs"},
                  {"action":"fry","fromSteps":["boiled_eggs"],"actionDescription":"Fry the eggs"}]}}""");
        assertEquals(List.of(slug.main().getSteps().get(0).getStepId()), slug.main().getSteps().get(1).getActionOn().getSteps());
        assertValid(slug);

        RecipeProcessOutputNormalizer.Normalized unknown = normalize("""
                {"main":{"name":"R","steps":[
                  {"action":"boil","ingredients":[{"ingredientId":"egg","name":"eggs","quantity":4,"unit":"piece"}],"actionDescription":"Boil","expectedOutput":"Boiled Eggs"},
                  {"action":"fry","fromSteps":["fried rice"],"actionDescription":"Fry"}]}}""");
        assertFalse(validator.validate(unknown.main(), unknown.preparations()).isValid());
    }

    // --- v3 shape: preparations + main ---

    @Test
    void preparationsBecomeNamedSubprocessesAndUsesBecomeProcessReferences() throws Exception {
        RecipeProcessOutputNormalizer.Normalized normalized = normalize("""
                {"preparations":[{"ref":"chopped_onions","output":"Chopped Onions","steps":[
                  {"action":"chop","ingredients":[{"ingredientId":"onion","name":"onions","quantity":2,"unit":"piece","preparationStyle":"chopped"}],"actionDescription":"Chop the onions"}]}],
                 "main":{"name":"Onion Fry","steps":[
                  {"action":"heat","ingredients":[{"ingredientId":"oil","name":"oil","quantity":2,"unit":"tbsp"}],"actionDescription":"Heat the oil","flameLevel":"medium"},
                  {"action":"saute","uses":["chopped_onions"],"actionDescription":"Fry the onions","duration":"8 minutes"},
                  {"action":"simmer","actionDescription":"Simmer until soft","duration":"5 minutes"}]}}""");

        GeneratedRecipeProcessDTO onions = normalized.preparations().get(0);
        assertEquals("chopped_onions", onions.getRef());
        assertEquals("Chopped Onions", onions.getName(), "the output name becomes the process name");
        assertEquals(List.of("chopped_onions"), normalized.main().getSteps().get(1).getActionOn().getProcesses());
        assertEquals("Chopped Onions", onions.getSteps().get(0).getExpectedOutput(), "the flow's last step names its output");
        assertEquals(1, normalized.repairs().size(), () -> normalized.repairs().toString());
        assertValid(normalized);
    }

    @Test
    void aPreparationsLastStepNamesItsOutput_keepingADifferentTextInItsDescription() throws Exception {
        RecipeProcessOutputNormalizer.Normalized normalized = normalize("""
                {"preparations":[{"ref":"m","output":"Marinated Chicken","steps":[
                  {"action":"cut","ingredients":[{"ingredientId":"chicken","name":"chicken","quantity":500,"unit":"g","preparationStyle":"bite-sized"}],"actionDescription":"Cut"},
                  {"action":"marinate","actionDescription":"Coat in yogurt","expectedOutput":"coated chicken"},
                  {"nodeType":"CONDITION","title":"Is it coated?","actionDescription":"Check"}]}],
                 "main":{"name":"R","steps":[{"action":"add","uses":["m"],"actionDescription":"Add the chicken"}]}}""");

        GeneratedRecipeStepDTO last = normalized.preparations().get(0).getSteps().get(1);
        assertEquals("Marinated Chicken", last.getExpectedOutput());
        assertEquals("Coat in yogurt (coated chicken)", last.getActionDescription());
        assertEquals("", normalized.preparations().get(0).getSteps().get(0).getExpectedOutput(), "only the last STEP is named");
        assertValid(normalized);
    }

    @Test
    void uncataloguedValuesAreKeptAsNamedCustomValues() throws Exception {
        RecipeProcessOutputNormalizer.Normalized normalized = normalize("""
                {"main":{"name":"R","steps":[
                  {"action":"spherify","ingredients":[{"ingredientId":"black-garlic-oil","name":"black garlic oil","quantity":1,"unit":"knob","preparationStyle":"spiralized"}],"actionDescription":"Spherify the oil"}]}}""");

        GeneratedRecipeStepDTO step = normalized.main().getSteps().get(0);
        assertEquals("custom", step.getAction());
        assertEquals("Spherify", step.getCustomActionName());
        GeneratedActionOnIngredientDTO ingredient = step.getActionOn().getIngredients().get(0);
        assertEquals("custom", ingredient.getIngredientId());
        assertEquals("black garlic oil", ingredient.getCustomIngredientName());
        assertEquals("custom", ingredient.getUnit());
        assertEquals("knob", ingredient.getCustomUnit());
        assertEquals("custom", ingredient.getPreparationStyle());
        assertEquals("spiralized", ingredient.getCustomPreparationStyle());
        assertEquals(4, normalized.repairs().size(), () -> normalized.repairs().toString());
        assertValid(normalized);
    }

    @Test
    void namesAndAliasesResolveToTheirCatalogIds_neverToAnUnrelatedEntry() throws Exception {
        RecipeProcessOutputNormalizer.Normalized normalized = normalize("""
                {"main":{"name":"R","steps":[
                  {"action":"brown","ingredients":[{"ingredientId":"999","name":"dhania","quantity":1,"unit":"tablespoon"}],"actionDescription":"Brown it"}]}}""");

        GeneratedRecipeStepDTO step = normalized.main().getSteps().get(0);
        assertEquals("sear", step.getAction(), "an action alias maps onto its catalog id");
        GeneratedActionOnIngredientDTO ingredient = step.getActionOn().getIngredients().get(0);
        assertEquals("cilantro", ingredient.getIngredientId(), "an unknown id falls back to its name's catalog entry");
        assertEquals("tbsp", ingredient.getUnit());
        assertValid(normalized);
    }

    @Test
    void unknownNumericIdWithoutAName_isLeftForTheValidator() throws Exception {
        RecipeProcessOutputNormalizer.Normalized normalized = normalize("""
                {"main":{"name":"R","steps":[{"action":"add","ingredients":[{"ingredientId":"424242","quantity":1,"unit":"tbsp"}],"actionDescription":"Add it"}]}}""");

        assertEquals("424242", normalized.main().getSteps().get(0).getActionOn().getIngredients().get(0).getIngredientId());
        assertFalse(validator.validate(normalized.main(), normalized.preparations()).isValid());
    }

    @Test
    void compoundStyleKeepsTheFirstAllowedStyleAndNotesTheRest() throws Exception {
        RecipeProcessOutputNormalizer.Normalized normalized = normalize("""
                {"main":{"name":"R","steps":[{"action":"add","ingredients":[{"ingredientId":"chicken","name":"chicken","quantity":500,"unit":"g","preparationStyle":"skinless, boneless"}],"actionDescription":"Add the chicken"}]}}""");

        GeneratedActionOnIngredientDTO chicken = normalized.main().getSteps().get(0).getActionOn().getIngredients().get(0);
        assertEquals("skinless", chicken.getPreparationStyle());
        assertEquals("boneless", chicken.getNotes());
        assertValid(normalized);
    }

    @Test
    void stepFieldsTheActionDoesNotTakeOrCannotBeRead_areDroppedOrNormalized() throws Exception {
        RecipeProcessOutputNormalizer.Normalized normalized = normalize("""
                {"main":{"name":"R","steps":[
                  {"action":"chop","ingredients":[{"ingredientId":"onion","name":"onion","quantity":1,"unit":"piece"}],"actionDescription":"Chop","duration":"2 minutes","flameLevel":"high"},
                  {"action":"saute","ingredients":[{"ingredientId":"onion","name":"onion","quantity":1,"unit":"piece"}],"actionDescription":"Fry","duration":"5-7 mins","flameLevel":"Medium High"},
                  {"action":"bake","ingredients":[{"ingredientId":"potato","name":"potato","quantity":2,"unit":"piece"}],"actionDescription":"Bake","temperatureValue":200,"duration":"a while"}]}}""");

        List<GeneratedRecipeStepDTO> steps = normalized.main().getSteps();
        assertNull(steps.get(0).getDuration());
        assertNull(steps.get(0).getFlameLevel());
        assertEquals("5 minutes", steps.get(1).getDuration(), "a range keeps its lower bound");
        assertEquals("medium-high", steps.get(1).getFlameLevel());
        assertNull(steps.get(2).getTemperatureValue(), "a temperature without a unit is unusable");
        assertNull(steps.get(2).getDuration());
        assertValid(normalized);
    }

    @Test
    void quantityRulesAreRepaired() throws Exception {
        RecipeProcessOutputNormalizer.Normalized normalized = normalize("""
                {"main":{"name":"R","steps":[{"action":"add","ingredients":[
                  {"ingredientId":"salt","name":"salt","quantity":1,"unit":"to-taste"},
                  {"ingredientId":"onion","name":"onion","quantity":2},
                  {"ingredientId":"water","name":"water"}],"actionDescription":"Add"}]}}""");

        List<GeneratedActionOnIngredientDTO> ingredients = normalized.main().getSteps().get(0).getActionOn().getIngredients();
        assertNull(ingredients.get(0).getQuantity(), "to-taste carries no number");
        assertEquals("piece", ingredients.get(1).getUnit(), "a bare number gets the ingredient's default unit");
        assertEquals("as-needed", ingredients.get(2).getUnit(), "add requires an amount; none given means as needed");
        assertValid(normalized);
    }

    @Test
    void structuralMistakesAreNotRepaired() throws Exception {
        RecipeProcessOutputNormalizer.Normalized normalized = normalize("""
                {"preparations":[{"ref":"paste","output":"Paste","steps":[{"action":"grind","ingredients":[{"ingredientId":"garlic","name":"garlic","quantity":4,"unit":"clove"}],"actionDescription":"Grind"}]}],
                 "main":{"name":"R","steps":[
                  {"action":"add","uses":["sauce"],"actionDescription":"Add the sauce"},
                  {"action":"fry","fromSteps":["nope"],"actionDescription":"Fry"}]}}""");

        List<String> errors = validator.validate(normalized.main(), normalized.preparations()).getErrors();
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("main.steps[0].uses[0] references an unknown preparation ref: sauce")), errors::toString);
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("main.steps[1].fromSteps[0]")), errors::toString);
    }

    @Test
    void annotationsAreFittedToTheStepsOwnTargets() throws Exception {
        RecipeProcessOutputNormalizer.Normalized normalized = normalize("""
                {"preparations":[{"ref":"onions","output":"Chopped Onions","steps":[{"action":"chop","ingredients":[{"ingredientId":"onion","name":"onions","quantity":2,"unit":"piece"}],"actionDescription":"Chop the @[onions](ingredient:onions)"}]}],
                 "main":{"name":"R","steps":[
                  {"stepId":"b1","action":"sear","ingredients":[{"ingredientId":"chicken","name":"chicken","quantity":500,"unit":"g"},{"ingredientId":"custom","name":"Kashmiri chilli paste","quantity":1,"unit":"tbsp"}],
                   "actionDescription":"Sear the @[chicken](ingredient:chicken) with @[chilli paste](ingredient:Kashmiri chilli paste) and @[salt](ingredient:salt)","expectedOutput":"browned chicken"},
                  {"action":"saute","uses":["onions"],"actionDescription":"Fry the @[onions](prepared:onions) and @[sauce](prepared:sauce)"},
                  {"action":"add","fromSteps":["b1"],"actionDescription":"Return the @[browned chicken](step:browned chicken) and @[it](step:zz)"},
                  {"nodeType":"CONDITION","title":"Is it done?","actionDescription":"Check the @[chicken](ingredient:chicken)"}]}}""");

        List<GeneratedRecipeStepDTO> steps = normalized.main().getSteps();
        assertEquals("Chop the @[onions](ingredient:onion)", normalized.preparations().get(0).getSteps().get(0).getActionDescription(),
                "an ingredient written by its name points at the entry's catalog id");
        assertEquals("Sear the @[chicken](ingredient:chicken) with @[chilli paste](ingredient:custom:Kashmiri chilli paste) and salt",
                steps.get(0).getActionDescription(), "a custom ingredient keeps its name; one the step doesn't list is plain text");
        assertEquals("Fry the @[onions](prepared:onions) and sauce", steps.get(1).getActionDescription());
        assertEquals("Return the @[browned chicken](step:b1) and it", steps.get(2).getActionDescription(),
                "a step written as its expected output points at that step");
        assertEquals("Check the chicken", steps.get(3).getActionDescription(), "a check is never annotated");
        assertTrue(normalized.repairs().stream().anyMatch(repair -> repair.contains("\"salt\"")), normalized.repairs()::toString);
        assertValid(normalized);
    }

    @Test
    void anAnnotatedDescriptionStillCountsAsADescription() throws Exception {
        RecipeProcessOutputNormalizer.Normalized normalized = normalize("""
                {"main":{"name":"R","steps":[
                  {"stepId":"a","action":"heat","ingredients":[{"ingredientId":"oil","name":"oil","quantity":1,"unit":"tbsp"}],"actionDescription":"@[oil](ingredient:oil)"},
                  {"action":"fry","fromSteps":["a"],"actionDescription":"Fry in the hot oil"}]}}""");

        assertEquals("oil", normalized.main().getSteps().get(0).getExpectedOutput(),
                "a used step's description becomes its expected output as plain words");
        assertValid(normalized);
    }

    @Test
    void aStepsTitleAndSummaryAreKept_andASummaryIsPlainSpokenText() throws Exception {
        RecipeProcessOutputNormalizer.Normalized normalized = normalize("""
                {"main":{"name":"R","steps":[
                  {"action":"heat","title":" Heat the oil ","actionDescription":"Heat the oil","summary":"Warm a little @[oil](ingredient:oil) in the pan."},
                  {"action":"fry","actionDescription":"Fry in the hot oil","summary":"  "}]}}""");

        List<GeneratedRecipeStepDTO> steps = normalized.main().getSteps();
        assertEquals("Heat the oil", steps.get(0).getTitle());
        assertEquals("Warm a little oil in the pan.", steps.get(0).getSummary());
        assertNull(steps.get(1).getTitle(), "no title means the editor derives one");
        assertNull(steps.get(1).getSummary(), "a blank summary is none");
        assertTrue(normalized.repairs().stream().anyMatch(repair -> repair.contains("summary")), normalized.repairs()::toString);
    }

    private RecipeProcessOutputNormalizer.Normalized normalize(String json) throws Exception {
        return normalizer.normalize(objectMapper.readValue(json, RecipeProcessOutput.class));
    }

    private void assertValid(RecipeProcessOutputNormalizer.Normalized normalized) {
        ProcessValidationResult result = validator.validate(normalized.main(), normalized.preparations());
        assertTrue(result.isValid(), () -> String.join("; ", result.getErrors()) + " (repairs: " + normalized.repairs() + ")");
    }

    /** The seed ingredients re-keyed by database-style numeric ids, like the application's ingredient catalog. */
    private static RecipeStepVocabularyProvider vocabularyWithDatabaseIds(Map<String, String> slugById) {
        List<IngredientDefinition> seed = RecipeStepVocabularyProvider.seedIngredients(StepCatalogResource.read());
        Map<String, IngredientDefinition> bySlug = new LinkedHashMap<>();
        seed.forEach(ingredient -> bySlug.put(ingredient.id(), ingredient));
        List<IngredientDefinition> ingredients = new java.util.ArrayList<>();
        slugById.forEach((id, slug) -> {
            IngredientDefinition source = bySlug.get(slug);
            ingredients.add(new IngredientDefinition(id, source.name(), source.category(), source.defaultUnit(), source.units(),
                    List.of(slug), source.preparationStyleSets()));
        });
        ingredients.add(bySlug.get("custom"));
        return new RecipeStepVocabularyProvider(() -> ingredients);
    }
}
