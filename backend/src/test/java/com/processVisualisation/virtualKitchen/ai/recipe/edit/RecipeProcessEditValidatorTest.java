package com.processVisualisation.virtualKitchen.ai.recipe.edit;

import com.processVisualisation.virtualKitchen.ai.recipe.edit.dto.EditSubprocessRefDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.edit.dto.EditTargetProcessDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.edit.dto.ProcessEditOperationDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessGenerationValidator;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutput;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutputNormalizer;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static com.processVisualisation.virtualKitchen.ai.recipe.edit.EditTestFixtures.ADD_ID;
import static com.processVisualisation.virtualKitchen.ai.recipe.edit.EditTestFixtures.BOIL_ID;
import static com.processVisualisation.virtualKitchen.ai.recipe.edit.EditTestFixtures.COOK_ID;
import static com.processVisualisation.virtualKitchen.ai.recipe.edit.EditTestFixtures.DRAIN_ID;
import static com.processVisualisation.virtualKitchen.ai.recipe.edit.EditTestFixtures.SAUCE_PROCESS_ID;
import static com.processVisualisation.virtualKitchen.ai.recipe.edit.EditTestFixtures.newStep;
import static com.processVisualisation.virtualKitchen.ai.recipe.edit.EditTestFixtures.op;
import static com.processVisualisation.virtualKitchen.ai.recipe.edit.EditTestFixtures.outIngredient;
import static com.processVisualisation.virtualKitchen.ai.recipe.edit.EditTestFixtures.output;
import static com.processVisualisation.virtualKitchen.ai.recipe.edit.EditTestFixtures.pastaContext;
import static com.processVisualisation.virtualKitchen.ai.recipe.edit.EditTestFixtures.patchDuration;
import static com.processVisualisation.virtualKitchen.ai.recipe.edit.EditTestFixtures.patchFlame;
import static com.processVisualisation.virtualKitchen.ai.recipe.edit.EditTestFixtures.patchUses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link RecipeProcessEditValidator}: operation-level checks, the simulated before/after structural
 * comparison, and the translation of aliases back to real node ids. Uses the real catalog, like
 * {@code RecipeProcessGenerationValidatorTest}.
 */
class RecipeProcessEditValidatorTest {

    private static final RecipeStepVocabularyProvider VOCABULARY = new RecipeStepVocabularyProvider();
    private final RecipeProcessEditValidator validator = new RecipeProcessEditValidator(
            new RecipeProcessGenerationValidator(VOCABULARY), new RecipeProcessOutputNormalizer());

    @Test
    void addStepBeforeDraining_isTranslatedToNodeIdsWithANewRef() {
        RecipeProcessEditValidator.Result result = validator.validate(output(
                op("ADD_STEP").after("s3").step(newStep("season", List.of(outIngredient("salt", null, "to-taste")), "Season the pasta with salt")).build()
        ), pastaContext());

        assertThat(result.errors()).isEmpty();
        ProcessEditOperationDTO added = result.operations().get(0);
        assertThat(added.getOp()).isEqualTo("ADD_STEP");
        assertThat(added.getAfter()).isEqualTo(COOK_ID);
        assertThat(added.getRef()).isEqualTo("new-1");
        assertThat(added.getStep().getStepId()).isEqualTo("new-1");
        assertThat(added.getStep().getAction()).isEqualTo("season");
        assertThat(added.getStep().getActionOn().getIngredients()).extracting("ingredientId").containsExactly("salt");
    }

    @Test
    void addStepAtStart_keepsStartMarker() {
        RecipeProcessEditValidator.Result result = validator.validate(output(
                op("ADD_STEP").after("START").step(newStep("wash", List.of(outIngredient("tomato", 2.0, "piece")), "Wash the tomatoes")).build()
        ), pastaContext());

        assertThat(result.errors()).isEmpty();
        assertThat(result.operations().get(0).getAfter()).isEqualTo(ProcessEditOperationDTO.START);
    }

    @Test
    void addStepStartingANewFlow_keepsTheNewFlowMarker_butAChecksCannotStartOne() {
        RecipeProcessEditValidator.Result added = validator.validate(output(
                op("ADD_STEP").after("NEW_FLOW").step(newStep("chop", List.of(outIngredient("onion", 2.0, "piece")), "Chop the onions")).build()
        ), pastaContext());
        assertThat(added.errors()).isEmpty();
        assertThat(added.operations().get(0).getAfter()).isEqualTo(ProcessEditOperationDTO.NEW_FLOW);

        RecipeProcessEditValidator.Result check = validator.validate(output(
                op("ADD_CONDITION").after("NEW_FLOW").title("Is it soft?").actionDescription("Check").build()
        ), pastaContext());
        assertThat(check.errors()).anyMatch(error -> error.contains("NEW_FLOW"));
    }

    @Test
    void aPreparationStepOutputIsOfferedAsAnAliasAndTranslatedBackWithItsStep() {
        EditTargetProcessDTO target = EditTestFixtures.pastaProcess();
        target.setSubprocesses(List.of(new EditSubprocessRefDTO(SAUCE_PROCESS_ID, "sauce-step", "Tomato Sauce")));
        RecipeProcessEditValidator.Result result = validator.validate(output(
                op("UPDATE_STEP").target("s3").set(patchUses(List.of("p1"))).build()
        ), RecipeProcessEditContext.of(target, null));

        assertThat(result.errors()).isEmpty();
        assertThat(result.operations().get(0).getStep().getActionOn().getProcesses()).containsExactly(SAUCE_PROCESS_ID + ":sauce-step");
    }

    @Test
    void annotationsReachTheModelAsAliasesAndComeBackAsTheEditorsIds() {
        EditTargetProcessDTO target = EditTestFixtures.pastaProcess();
        target.setSubprocesses(List.of(new EditSubprocessRefDTO(SAUCE_PROCESS_ID, "sauce-step", "Tomato Sauce")));
        target.getNodes().get(2).getContent().setActionDescription(
                "Cook the @[pasta](step:" + ADD_ID + ") with @[sauce](prepared:" + SAUCE_PROCESS_ID + ":sauce-step) and @[old](prepared:99)");
        RecipeProcessEditContext context = RecipeProcessEditContext.of(target, null);

        assertThat(context.steps().get(2).getActionDescription())
                .isEqualTo("Cook the @[pasta](step:s2) with @[sauce](prepared:p1) and old");

        RecipeProcessEditOutput.StepPatch patch = new RecipeProcessEditOutput.StepPatch(null, null,
                "Cook the @[pasta](step:s2) in the @[sauce](prepared:p1) with @[salt](ingredient:salt)",
                null, null, null, null, null, null, List.of("s2"), null, null, List.of("p1"));
        RecipeProcessEditValidator.Result result = validator.validate(output(op("UPDATE_STEP").target("s3").set(patch).build()), context);

        assertThat(result.errors()).isEmpty();
        assertThat(result.operations().get(0).getStep().getActionDescription()).isEqualTo(
                "Cook the @[pasta](step:" + ADD_ID + ") in the @[sauce](prepared:" + SAUCE_PROCESS_ID + ":sauce-step) with salt");
    }

    @Test
    void anAddedStepsAnnotationsUseTheEditorsIds() {
        RecipeProcessOutput.Node step = new RecipeProcessOutput.Node(null, null, "toss", null,
                List.of(outIngredient("salt", null, "to-taste")), null, List.of("s4"),
                "Toss the @[drained pasta](step:s4) with @[salt](ingredient:salt)", null,
                null, null, null, null, null, null, null, null, null);
        RecipeProcessEditValidator.Result result = validator.validate(output(op("ADD_STEP").after("s4").step(step).build()), pastaContext());

        assertThat(result.errors()).isEmpty();
        assertThat(result.operations().get(0).getStep().getActionDescription())
                .isEqualTo("Toss the @[drained pasta](step:" + DRAIN_ID + ") with @[salt](ingredient:salt)");
    }

    @Test
    void updateStepDuration_carriesOnlyTheChangedField() {
        RecipeProcessEditValidator.Result result = validator.validate(output(
                op("UPDATE_STEP").target("s3").set(patchDuration("8 minutes")).build()
        ), pastaContext());

        assertThat(result.errors()).isEmpty();
        ProcessEditOperationDTO update = result.operations().get(0);
        assertThat(update.getTarget()).isEqualTo(COOK_ID);
        assertThat(update.getStep().getDuration()).isEqualTo("8 minutes");
        assertThat(update.getStep().getAction()).isNull();
        assertThat(update.getStep().getActionOn()).isNull();
    }

    @Test
    void updateStepWithUnknownFlameLevel_isRejected() {
        RecipeProcessEditValidator.Result result = validator.validate(output(
                op("UPDATE_STEP").target("s3").set(patchFlame("scorching")).build()
        ), pastaContext());

        assertThat(result.errors()).anyMatch(error -> error.contains("step s3.flameLevel"));
    }

    @Test
    void existingProblemsInUntouchedSteps_doNotBlockAnEdit() {
        EditTargetProcessDTO process = EditTestFixtures.pastaProcess();
        // A hand-made step with a value the AI catalog doesn't know.
        process.getNodes().get(0).getContent().setFlameLevel("volcanic");
        RecipeProcessEditContext context = RecipeProcessEditContext.of(process, null);

        RecipeProcessEditValidator.Result result = validator.validate(output(
                op("UPDATE_STEP").target("s3").set(patchDuration("8 minutes")).build()
        ), context);

        assertThat(result.errors()).isEmpty();
    }

    @Test
    void unknownTarget_isRejectedWithAHint() {
        RecipeProcessEditValidator.Result result = validator.validate(output(
                op("DELETE_NODE").target("s9").build()
        ), pastaContext());

        assertThat(result.errors()).singleElement().asString().contains("unknown node: s9");
        assertThat(result.operations()).isEmpty();
    }

    @Test
    void referringToANodeAfterDeletingIt_isRejected() {
        RecipeProcessEditValidator.Result result = validator.validate(output(
                op("DELETE_NODE").target("s2").build(),
                op("UPDATE_STEP").target("s2").set(patchDuration("2 minutes")).build()
        ), pastaContext());

        assertThat(result.errors()).anyMatch(error -> error.contains("already removed"));
    }

    @Test
    void deletingTheLastStep_isValidAndTranslated() {
        RecipeProcessEditValidator.Result result = validator.validate(output(
                op("DELETE_NODE").target("s4").build()
        ), pastaContext());

        assertThat(result.errors()).isEmpty();
        assertThat(result.operations()).singleElement().extracting(ProcessEditOperationDTO::getTarget).isEqualTo(DRAIN_ID);
    }

    @Test
    void deletingEveryNode_isRejected() {
        RecipeProcessEditValidator.Result result = validator.validate(output(
                op("DELETE_NODE").target("s1").build(), op("DELETE_NODE").target("s2").build(),
                op("DELETE_NODE").target("s3").build(), op("DELETE_NODE").target("s4").build()
        ), pastaContext());

        assertThat(result.errors()).anyMatch(error -> error.contains("remove every node"));
    }

    @Test
    void conditionAddedFirst_breaksPlacementRule() {
        RecipeProcessEditValidator.Result result = validator.validate(output(
                op("ADD_CONDITION").after("START").title("Is the water boiling?").actionDescription("Check for bubbles").build()
        ), pastaContext());

        assertThat(result.errors()).anyMatch(error -> error.contains("must come immediately after the STEP"));
    }

    @Test
    void conditionAfterAStep_isValid() {
        RecipeProcessEditValidator.Result result = validator.validate(output(
                op("ADD_CONDITION").after("s3").title("Is the pasta al dente?").actionDescription("Bite a piece").build()
        ), pastaContext());

        assertThat(result.errors()).isEmpty();
        assertThat(result.operations().get(0).getStep().getNodeType()).isEqualTo("CONDITION");
        assertThat(result.operations().get(0).getStep().getExpectedResult()).isEqualTo("success");
    }

    @Test
    void replaceIngredientWithoutTarget_expandsToEveryStepUsingIt_andKeepsTheAmount() {
        RecipeProcessEditValidator.Result result = validator.validate(output(
                op("REPLACE_INGREDIENT").from("water").ingredient(outIngredient("milk", null, null)).build()
        ), pastaContext());

        assertThat(result.errors()).isEmpty();
        ProcessEditOperationDTO replace = result.operations().get(0);
        assertThat(replace.getTarget()).isEqualTo(BOIL_ID);
        assertThat(replace.getIngredientId()).isEqualTo("water");
        assertThat(replace.getIngredient().getIngredientId()).isEqualTo("milk");
        assertThat(replace.getIngredient().getQuantity()).isEqualTo(2.0);
        assertThat(replace.getIngredient().getUnit()).isEqualTo("l");
    }

    @Test
    void updateIngredientTheStepDoesNotHave_listsWhatItHas() {
        RecipeProcessEditValidator.Result result = validator.validate(output(
                op("UPDATE_INGREDIENT").target("s2").ingredient(outIngredient("salt", 1.0, "tsp")).build()
        ), pastaContext());

        assertThat(result.errors()).singleElement().asString().contains("has no ingredient salt").contains("pasta");
    }

    @Test
    void updateIngredientQuantity_isValid() {
        RecipeProcessEditValidator.Result result = validator.validate(output(
                op("UPDATE_INGREDIENT").target("s2").ingredient(outIngredient("pasta", 250.0, null)).build()
        ), pastaContext());

        assertThat(result.errors()).isEmpty();
        assertThat(result.operations().get(0).getTarget()).isEqualTo(ADD_ID);
        assertThat(result.operations().get(0).getIngredient().getQuantity()).isEqualTo(250.0);
    }

    @Test
    void moveNode_andLaterReferenceToAnAddedStep_useRefs() {
        RecipeProcessEditValidator.Result result = validator.validate(output(
                op("ADD_STEP").after("s4").step(new RecipeProcessOutput.Node(null, "n1", "garnish", null,
                        List.of(outIngredient("cilantro", null, "as-needed")), null, null, "Garnish with coriander", null,
                        null, null, null, null, null, null, null, null, null)).build(),
                op("MOVE_NODE").target("s1").after("s2").build()
        ), pastaContext());

        assertThat(result.errors()).isEmpty();
        assertThat(result.operations()).extracting(ProcessEditOperationDTO::getOp).containsExactly("ADD_STEP", "MOVE_NODE");
        assertThat(result.operations().get(1).getTarget()).isEqualTo(BOIL_ID);
        assertThat(result.operations().get(1).getAfter()).isEqualTo(ADD_ID);
    }

    @Test
    void movingAStepAfterItsConsumer_breaksTheStepOutputRule() {
        // s3 cooks "pasta in water" from s2; moving s2 after s3 leaves s3 referencing a later step.
        RecipeProcessEditValidator.Result result = validator.validate(output(
                op("MOVE_NODE").target("s2").after("s3").build()
        ), pastaContext());

        assertThat(result.errors()).anyMatch(error -> error.contains("references a later step"));
    }

    @Test
    void clarification_returnsNoOperations() {
        RecipeProcessEditValidator.Result result = validator.validate(
                new RecipeProcessEditOutput("Need the amount.", List.of(), "What should the quantity be?"), pastaContext());

        assertThat(result.errors()).isEmpty();
        assertThat(result.operations()).isEmpty();
    }

    @Test
    void emptyOperationsWithoutClarification_isRejected() {
        RecipeProcessEditValidator.Result result = validator.validate(
                new RecipeProcessEditOutput("Nothing.", List.of(), null), pastaContext());

        assertThat(result.errors()).anyMatch(error -> error.contains("operations is empty"));
    }

    @Test
    void tooManyOperations_isRejected() {
        RecipeProcessEditOutput.Operation update = op("UPDATE_STEP").target("s3").set(patchDuration("8 minutes")).build();
        RecipeProcessEditValidator.Result result = validator.validate(new RecipeProcessEditOutput("Many.",
                Collections.nCopies(RecipeProcessEditOutputSchema.MAX_OPERATIONS + 1, update), null), pastaContext());

        assertThat(result.errors()).anyMatch(error -> error.contains("too many operations"));
    }

    @Test
    void updateStepOnACondition_pointsToUpdateCondition() {
        EditTargetProcessDTO process = EditTestFixtures.pastaProcess();
        RecipeProcessEditContext context = RecipeProcessEditContext.of(process, null);
        RecipeProcessEditValidator.Result added = validator.validate(output(
                op("ADD_CONDITION").after("s3").stepId("c1").title("Is it done?").actionDescription("Taste").build(),
                op("UPDATE_STEP").target("c1").set(patchDuration("1 minutes")).build()
        ), context);

        assertThat(added.errors()).anyMatch(error -> error.contains("use UPDATE_CONDITION"));
    }
}
