package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationPromptBuilder.BatchTarget;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationPromptBuilder.References;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService.VisualTarget;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Focused Cooking View's own logic, on a simple tomato recipe: chop onions, add oil, add onions, add tomatoes,
 * add turmeric and stir, cook until soft. Action-aware focus against the real step catalog, and the prompt text the
 * image model receives: one scene described identically for every step, framing that changes only with the focus.
 */
class FocusedCookingViewTest {

    private static final RecipeStepVocabularyProvider VOCABULARY = new RecipeStepVocabularyProvider();
    private static final SceneContext KITCHEN = new SceneContext("a black round frying pan", "a black gas stove",
            "a light grey stone countertop", "a wooden cutting board", "a plain steel mixing bowl", "a plain white plate");

    private final RecipeProcessVisualizationPromptBuilder builder = new RecipeProcessVisualizationPromptBuilder();
    private final VisualizationImageProperties properties = new VisualizationImageProperties();
    private final VisualizationImageProfiles profiles = new VisualizationImageProfiles(properties, VOCABULARY);

    // --- action-aware focus ---------------------------------------------------------------------------------

    @Test
    void theCameraFollowsTheAction() {
        assertEquals(VisualFocus.CUTTING_BOARD, profiles.focusFor("chop"));
        assertEquals(VisualFocus.CUTTING_BOARD, profiles.focusFor("peel"));
        assertEquals(VisualFocus.COOKING_VESSEL, profiles.focusFor("add"));
        assertEquals(VisualFocus.COOKING_VESSEL, profiles.focusFor("stir"));
        assertEquals(VisualFocus.COOKING_VESSEL, profiles.focusFor("fry"));
        assertEquals(VisualFocus.COOKING_VESSEL, profiles.focusFor("boil"));
        assertEquals(VisualFocus.COOKING_VESSEL, profiles.focusFor("cook"));
        assertEquals(VisualFocus.COOKING_VESSEL, profiles.focusFor("rest"));
        assertEquals(VisualFocus.PREP_BOWL, profiles.focusFor("marinate"));
        assertEquals(VisualFocus.PREP_BOWL, profiles.focusFor("knead"));
        assertEquals(VisualFocus.PLATE, profiles.focusFor("serve"));
    }

    @Test
    void unknownCustomAndMissingActionsLookAtTheCookingVessel() {
        assertEquals(VisualFocus.COOKING_VESSEL, profiles.focusFor("custom"));
        assertEquals(VisualFocus.COOKING_VESSEL, profiles.focusFor("levitate"));
        assertEquals(VisualFocus.COOKING_VESSEL, profiles.focusFor(null));
    }

    @Test
    void theFocusOfAPurposeOrCategoryIsConfigurable_andABadNameFallsBack() {
        properties.getFocusByPurpose().put(VisualizationImageProperties.MIXING, "prep-bowl");
        properties.getFocusByCategory().put("cutting", "nonsense");
        properties.setDefaultFocus("also nonsense");

        assertEquals(VisualFocus.PREP_BOWL, profiles.focusFor("whisk"));
        assertEquals(VisualFocus.CUTTING_BOARD, profiles.focusFor("chop"), "a bad category entry is skipped");
        assertEquals(VisualFocus.COOKING_VESSEL, profiles.focusFor("add"), "a bad default falls back to the vessel");
    }

    @Test
    void thePromptWriterMayNameAnyFocusExceptTheStoryboard() {
        assertEquals(Optional.of(VisualFocus.PREP_BOWL), VisualFocus.parse(" prep bowl "));
        assertEquals(Optional.of(VisualFocus.COOKING_VESSEL), VisualFocus.parse("cooking-vessel"));
        assertEquals(Optional.empty(), VisualFocus.parse("STORYBOARD"));
        assertEquals(Optional.empty(), VisualFocus.parse("wide shot"));
        assertEquals(Optional.empty(), VisualFocus.parse(null));
    }

    @Test
    void referenceChainingNeedsTheFocusedView() {
        assertTrue(profiles.focusedViewEnabled());
        assertTrue(profiles.referenceChainEnabled());

        properties.setFocusedViewEnabled(false);

        assertFalse(profiles.focusedViewEnabled());
        assertFalse(profiles.referenceChainEnabled(), "chaining is part of the focused view");
    }

    // --- what the text model is asked ------------------------------------------------------------------------

    @Test
    void theBatchPromptGivesEveryStepItsDefaultFocus_andAsksForTheEquipmentOnce() {
        List<BatchTarget> targets = tomatoTargets();
        Map<String, VisualFocus> focusById = new LinkedHashMap<>();
        targets.forEach(target -> focusById.put(target.id(), profiles.focusFor(target.target().step().actionId())));

        String prompt = builder.buildFocusedBatchUserPrompt(targets, focusById, "Tomato Masala", true);

        assertTrue(prompt.startsWith("DISH: Tomato Masala"));
        assertTrue(section(prompt, "m1:").contains("Default focus: CUTTING_BOARD"), prompt);
        for (String id : List.of("m2:", "m3:", "m4:", "m5:", "m6:")) {
            assertTrue(section(prompt, id).contains("Default focus: COOKING_VESSEL"), id);
        }
        assertTrue(section(prompt, "m4:").contains("Continues from: Onions sizzling in oil"), "state continuity is kept");
        assertTrue(prompt.contains("SCENE: also write dishContext and scene for this recipe."));
        assertTrue(prompt.endsWith("ids: m1, m2, m3, m4, m5, m6."));
    }

    @Test
    void theSystemPromptKeepsTheSceneOutOfTheWritersHands_andPutsAccuracyFirst() {
        String system = builder.buildFocusedBatchSystemPrompt();

        assertTrue(system.contains("\"focus\"") && system.contains("\"state\"") && system.contains("\"action\""));
        assertTrue(system.contains("you never describe them"), "kitchen, camera and style are not the writer's");
        assertTrue(system.contains("everything already in the vessel"), "earlier ingredients stay in the pan");
        assertTrue(system.contains("Never show the future"));
        assertTrue(system.contains("Never show extras"));
        assertFalse(system.contains("styleGuide"), "no free-text style guide to drift");
    }

    // --- what the image model receives ------------------------------------------------------------------------

    @Test
    void everyStepOfARecipeDescribesItsSceneInTheSameWords() {
        List<String> panSteps = List.of(
                "A thin layer of oil shimmering in the pan. Oil being poured in from a small bottle.",
                "Raw chopped white onions in shimmering oil. Chopped onions being tipped in from the board.",
                "Softened translucent chopped onions in oil. Chopped red tomatoes being tipped in from a small bowl.",
                "Softened onions and tomato pieces dusted with yellow turmeric. A wooden spatula stirring the mixture.",
                "A soft, thick red-orange onion and tomato mixture. Gentle bubbling with a little steam.");

        List<List<String>> lines = new ArrayList<>();
        for (String step : panSteps) {
            lines.add(List.of(builder.buildFocusedImageRequestText(step, VisualFocus.COOKING_VESSEL, KITCHEN,
                    properties.getScene(), "Part of making Tomato Masala", References.NONE).split("\n")));
        }

        String framing = "Close-up photo, camera about 50 degrees above the counter looking down. A black round frying pan "
                + "on a black gas stove fills most of the frame, with a small strip of light grey stone countertop around it.";
        for (int i = 0; i < panSteps.size(); i++) {
            List<String> text = lines.get(i);
            assertEquals(framing, text.get(0), "same camera, pan and stove in every step");
            assertEquals(panSteps.get(i), text.get(1), "then the step's own state and action");
            assertEquals("Soft natural light, photorealistic, minimal home cooking setup, plain uncluttered background.", text.get(2));
            assertEquals(RecipeProcessVisualizationPromptBuilder.FOCUSED_GUARDRAIL, text.get(3));
            assertEquals("Context: Part of making Tomato Masala", text.get(4));
            assertEquals(5, text.size(), "no reference instruction without a reference");
        }
    }

    @Test
    void theFramingChangesOnlyWhenTheFocusDoes() {
        String chop = focusedText("Half an onion cut into small dice. A chef's knife chopping.", VisualFocus.CUTTING_BOARD);
        String fry = focusedText("Onions sizzling in oil.", VisualFocus.COOKING_VESSEL);
        String serve = focusedText("The finished masala in a neat mound.", VisualFocus.PLATE);

        assertTrue(chop.contains("A wooden cutting board on a light grey stone countertop fills most of the frame."), chop);
        assertFalse(chop.contains("frying pan"), "the pan is not in a cutting-board image");
        assertTrue(fry.contains("A black round frying pan on a black gas stove fills most of the frame"), fry);
        assertTrue(serve.contains("A plain white plate on a light grey stone countertop fills most of the frame."), serve);
        // Camera angle, lighting and style never change.
        for (String text : List.of(chop, fry, serve)) {
            assertTrue(text.startsWith("Close-up photo, camera about 50 degrees above the counter looking down."), text);
            assertTrue(text.contains("Soft natural light, photorealistic"), text);
        }
        assertNotEquals(chop.split("\n")[0], fry.split("\n")[0]);
    }

    @Test
    void theCameraAngleAndStyleComeFromConfiguration() {
        properties.getScene().setCameraAngle(35);
        properties.getScene().setLighting("warm evening light");

        String text = focusedText("Onions sizzling in oil.", VisualFocus.COOKING_VESSEL);

        assertTrue(text.contains("camera about 35 degrees above the counter"), text);
        assertTrue(text.contains("Warm evening light, photorealistic"), text);
    }

    @Test
    void aRecipeWithoutItsOwnEquipmentUsesTheConfiguredDefaults() {
        SceneContext partial = new SceneContext("a steel kadai", null, " ", null, null, null);

        SceneContext equipment = SceneContext.orDefaults(partial, properties.getScene().equipment());

        assertEquals("a steel kadai", equipment.vessel());
        assertEquals("a black gas stove", equipment.stove());
        assertEquals("a light grey stone countertop", equipment.surface());
        assertEquals(properties.getScene().equipment(), SceneContext.orDefaults(null, properties.getScene().equipment()));
    }

    @Test
    void theReferenceInstructionSaysWhichImagesWereSent() {
        String anchorOnly = focusedText("Oil in the pan.", VisualFocus.COOKING_VESSEL, new References(true, false));
        String both = focusedText("Onions in oil.", VisualFocus.COOKING_VESSEL, new References(true, true));
        String previousOnly = focusedText("Onions in oil.", VisualFocus.COOKING_VESSEL, new References(false, true));

        assertTrue(anchorOnly.endsWith(RecipeProcessVisualizationPromptBuilder.REFERENCE_INSTRUCTION));
        assertTrue(both.endsWith(RecipeProcessVisualizationPromptBuilder.ANCHOR_AND_PREVIOUS_INSTRUCTION));
        assertTrue(previousOnly.endsWith(RecipeProcessVisualizationPromptBuilder.PREVIOUS_INSTRUCTION));
        assertEquals(2, new References(true, true).count());
    }

    @Test
    void theSceneAnchorIsTheEmptyVesselFramedLikeACookingStep() {
        String scene = builder.buildFocusedScenePrompt(KITCHEN, properties.getScene());
        String step = focusedText("Onions sizzling in oil.", VisualFocus.COOKING_VESSEL);

        assertEquals(step.split("\n")[0], scene.split("\n")[0], "same framing as the steps drawn against it");
        assertTrue(scene.contains("absolutely no food"), scene);
    }

    @Test
    void anActionContextReadsAsStateThenAction() {
        assertEquals("Softened onions in oil. Tomatoes being tipped in.",
                new ActionContext(VisualFocus.COOKING_VESSEL, "Softened onions in oil", " Tomatoes being tipped in. ").describe());
        assertEquals("Gentle bubbling.", new ActionContext(VisualFocus.COOKING_VESSEL, " ", "Gentle bubbling").describe());
        assertEquals("", new ActionContext(VisualFocus.COOKING_VESSEL, null, "").describe());
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private String focusedText(String stepText, VisualFocus focus) {
        return focusedText(stepText, focus, References.NONE);
    }

    private String focusedText(String stepText, VisualFocus focus, References references) {
        return builder.buildFocusedImageRequestText(stepText, focus, KITCHEN, properties.getScene(), null, references);
    }

    /** The text of one id's entry in the batch prompt, up to the next id. */
    private static String section(String prompt, String id) {
        int start = prompt.indexOf(id);
        int next = prompt.indexOf("\nm", start + 1);
        return next < 0 ? prompt.substring(start) : prompt.substring(start, next);
    }

    static List<BatchTarget> tomatoTargets() {
        List<RecipeProcessVisualizationInput> steps = List.of(
                step("Chop", "chop", "Chop the onions finely", "Finely chopped onions", null),
                step("Add", "add", "Add oil to the pan", "Hot oil in the pan", "Finely chopped onions"),
                step("Add", "add", "Add the onions", "Onions sizzling in oil", "Hot oil in the pan"),
                step("Add", "add", "Add the tomatoes", "Onions and tomatoes in the pan", "Onions sizzling in oil"),
                step("Stir", "stir", "Add turmeric and stir", "Turmeric mixed in", "Onions and tomatoes in the pan"),
                step("Cook", "cook", "Cook until the mixture softens", "Soft tomato masala", "Turmeric mixed in"));
        List<BatchTarget> targets = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            targets.add(new BatchTarget("m" + (i + 1), new VisualTarget("node-" + i, steps.get(i), null)));
        }
        return targets;
    }

    private static RecipeProcessVisualizationInput step(String action, String actionId, String description,
                                                        String expectedOutput, String previous) {
        return new RecipeProcessVisualizationInput(action, List.of(), List.of(), description, expectedOutput,
                "", "", "", previous, actionId);
    }
}
