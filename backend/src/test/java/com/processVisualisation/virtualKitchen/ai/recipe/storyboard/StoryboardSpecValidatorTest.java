package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.RecipeStoryboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.processVisualisation.virtualKitchen.ai.recipe.storyboard.StoryboardTestFixtures.alias;
import static com.processVisualisation.virtualKitchen.ai.recipe.storyboard.StoryboardTestFixtures.layeredPlan;
import static com.processVisualisation.virtualKitchen.ai.recipe.storyboard.StoryboardTestFixtures.layeredRecipe;
import static com.processVisualisation.virtualKitchen.ai.recipe.storyboard.StoryboardTestFixtures.panel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryboardSpecValidatorTest {

    private StoryboardSource source;

    @BeforeEach
    void setUp() {
        source = StoryboardTestFixtures.source(layeredRecipe());
    }

    @Test
    void aGoodPlan_isAccepted_andItsPanelsCarryTheRealNodeIds() {
        StoryboardSpecValidator.Result result = StoryboardSpecValidator.validate(layeredPlan(source), source, 16);

        assertTrue(result.clean(), () -> String.join("; ", result.allProblems()));
        assertEquals(7, result.panels().size());
        // A grouped panel keeps every node it stands for, the check included.
        assertEquals(List.of("coat", "rest"), result.panels().get(1).getSourceStepIds());
        assertEquals(List.of("heat", "addChicken", "cook", "check"), result.panels().get(5).getSourceStepIds());
        assertEquals(StoryboardSource.PREPARATION, result.panels().get(0).getGroup());
        assertEquals(StoryboardSource.MAIN, result.panels().get(6).getGroup());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), result.panels().stream().map(RecipeStoryboard.Panel::getSequence).toList());
    }

    @Test
    void aPanelThatUsesPreparedComponents_referencesThePanelsThatMadeThem() {
        // The planner linked "add the chicken" to the marinade panel but forgot the rice and the onions.
        StoryboardSpecValidator.Result result = StoryboardSpecValidator.validate(layeredPlan(source), source, 16);

        RecipeStoryboard.Panel layering = result.panels().get(6);
        assertEquals(List.of("P4", "P5"), layering.getReferencePanels());
        assertEquals(List.of("P6", "P4", "P5"), layering.getDependsOn());
        assertTrue(result.linkProblems().isEmpty());
    }

    @Test
    void aStepInNoPanel_isAnError_unlessItIsListedAsOmitted() {
        List<StoryboardPlan.Panel> panels = new ArrayList<>(layeredPlan(source).panels());
        panels.set(1, panel("P2", 2, "PREPARATION", List.of(alias(source, "coat")), List.of("P1"), List.of("P1")));

        StoryboardSpecValidator.Result missing = StoryboardSpecValidator.validate(
                new StoryboardPlan("Biryani", "", panels, List.of()), source, 16);
        assertFalse(missing.usable());
        assertTrue(missing.errors().get(0).contains(alias(source, "rest")), missing.errors().toString());

        StoryboardSpecValidator.Result omitted = StoryboardSpecValidator.validate(new StoryboardPlan("Biryani", "", panels,
                List.of(new StoryboardPlan.Omitted(alias(source, "rest"), "resting shows nothing new"))), source, 16);
        assertTrue(omitted.clean(), () -> String.join("; ", omitted.allProblems()));
        assertEquals("rest", omitted.omitted().get(0).getStepId());
        assertEquals("resting shows nothing new", omitted.omitted().get(0).getReason());
    }

    @Test
    void unknownStepIds_emptyPanels_andTooManyPanels_areErrors() {
        StoryboardSpecValidator.Result unknown = StoryboardSpecValidator.validate(new StoryboardPlan("x", "", List.of(
                panel("P1", 1, "MAIN", List.of("m99"), List.of(), List.of())), List.of()), source, 16);
        assertTrue(unknown.errors().stream().anyMatch(error -> error.contains("unknown step id \"m99\"")));

        StoryboardSpecValidator.Result empty = StoryboardSpecValidator.validate(new StoryboardPlan("x", "", List.of(), List.of()), source, 16);
        assertEquals(List.of("The storyboard has no panels"), empty.errors());
        assertFalse(StoryboardSpecValidator.validate(null, source, 16).usable());

        StoryboardSpecValidator.Result tooMany = StoryboardSpecValidator.validate(layeredPlan(source), source, 4);
        assertTrue(tooMany.errors().get(0).contains("7 panels; use at most 4"), tooMany.errors().toString());
    }

    @Test
    void preparationComesFirst_mainPanelsFollowTheCookingOrder_andAPanelNeverMixesTheTwo() {
        List<StoryboardPlan.Panel> swapped = new ArrayList<>(layeredPlan(source).panels());
        StoryboardPlan.Panel first = swapped.get(5);
        StoryboardPlan.Panel second = swapped.get(6);
        swapped.set(5, panel("P7", 6, "MAIN", second.sourceStepIds(), List.of(), List.of()));
        swapped.set(6, panel("P6", 7, "MAIN", first.sourceStepIds(), List.of(), List.of()));
        assertTrue(StoryboardSpecValidator.validate(new StoryboardPlan("x", "", swapped, List.of()), source, 16).errors()
                .stream().anyMatch(error -> error.contains("out of cooking order")));

        List<StoryboardPlan.Panel> late = new ArrayList<>(layeredPlan(source).panels());
        late.add(panel("P8", 8, "PREPARATION", List.of(alias(source, "slice")), List.of(), List.of()));
        assertTrue(StoryboardSpecValidator.validate(new StoryboardPlan("x", "", late, List.of()), source, 16).errors()
                .stream().anyMatch(error -> error.contains("preparation comes first")));

        List<StoryboardPlan.Panel> mixed = new ArrayList<>(layeredPlan(source).panels());
        mixed.set(4, panel("P5", 5, "PREPARATION", List.of(alias(source, "slice"), alias(source, "fry"), alias(source, "heat")),
                List.of(), List.of()));
        assertTrue(StoryboardSpecValidator.validate(new StoryboardPlan("x", "", mixed, List.of()), source, 16).errors()
                .stream().anyMatch(error -> error.contains("mixes preparation steps and main cooking steps")));
    }

    @Test
    void linksToUnknownLaterOrOwnPanels_areReported_andDropped_soNoCycleSurvives() {
        List<StoryboardPlan.Panel> panels = new ArrayList<>(layeredPlan(source).panels());
        // P1 depends on P2 while P2 depends on P1 (a cycle), P3 references itself, P4 names a panel that does not exist.
        panels.set(0, panel("P1", 1, "PREPARATION", List.of(alias(source, "mix")), List.of("P2"), List.of()));
        panels.set(2, panel("P3", 3, "PREPARATION", List.of(alias(source, "boil")), List.of(), List.of("P3")));
        panels.set(3, panel("P4", 4, "PREPARATION", List.of(alias(source, "drain")), List.of("P3", "P42"), List.of("P3")));

        StoryboardSpecValidator.Result result = StoryboardSpecValidator.validate(
                new StoryboardPlan("x", "", panels, List.of()), source, 16);

        assertTrue(result.usable());
        assertFalse(result.clean());
        assertEquals(3, result.linkProblems().size(), result.linkProblems().toString());
        assertTrue(result.panels().get(0).getDependsOn().isEmpty());
        assertEquals(List.of("P1"), result.panels().get(1).getDependsOn());
        assertTrue(result.panels().get(2).getReferencePanels().isEmpty());
        assertEquals(List.of("P3"), result.panels().get(3).getDependsOn());
    }

    @Test
    void panelsAreOrderedByTheirSequence_andRenumbered_andDuplicateIdsAreAnError() {
        List<StoryboardPlan.Panel> shuffled = new ArrayList<>(layeredPlan(source).panels());
        java.util.Collections.reverse(shuffled);
        StoryboardSpecValidator.Result result = StoryboardSpecValidator.validate(
                new StoryboardPlan("x", "", shuffled, List.of()), source, 16);
        assertTrue(result.clean(), () -> String.join("; ", result.allProblems()));
        assertEquals(List.of("P1", "P2", "P3", "P4", "P5", "P6", "P7"),
                result.panels().stream().map(RecipeStoryboard.Panel::getPanelId).toList());

        List<StoryboardPlan.Panel> duplicate = new ArrayList<>(layeredPlan(source).panels());
        duplicate.set(2, panel("P1", 3, "PREPARATION", List.of(alias(source, "boil")), List.of(), List.of()));
        assertTrue(StoryboardSpecValidator.validate(new StoryboardPlan("x", "", duplicate, List.of()), source, 16).errors()
                .stream().anyMatch(error -> error.contains("appears more than once")));
    }

    @Test
    void aPanelWithoutPromptOrDescription_isAnError() {
        List<StoryboardPlan.Panel> panels = new ArrayList<>(layeredPlan(source).panels());
        panels.set(0, new StoryboardPlan.Panel("P1", 1, "PREPARATION", null, " ", List.of(alias(source, "mix")), null,
                List.of(), List.of(), null, null, null));

        List<String> errors = StoryboardSpecValidator.validate(new StoryboardPlan("x", "", panels, List.of()), source, 16).errors();

        assertTrue(errors.contains("Panel P1 has no description"), errors.toString());
        assertTrue(errors.contains("Panel P1 has no imagePrompt"), errors.toString());
    }
}
