package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.RecipeStoryboard;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationImageProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryboardPromptBuilderTest {

    private final StoryboardPromptBuilder builder = new StoryboardPromptBuilder();
    private final VisualizationImageProperties.Scene style = new VisualizationImageProperties.Scene();

    @Test
    void theGridIsAsSquareAsThePanelCountAllows() {
        assertEquals(new StoryboardLayout(1, 1), StoryboardLayout.of(1));
        assertEquals(new StoryboardLayout(1, 2), StoryboardLayout.of(2));
        assertEquals(new StoryboardLayout(2, 2), StoryboardLayout.of(3));
        assertEquals(new StoryboardLayout(2, 2), StoryboardLayout.of(4));
        assertEquals(new StoryboardLayout(2, 3), StoryboardLayout.of(5));
        assertEquals(new StoryboardLayout(2, 3), StoryboardLayout.of(6));
        assertEquals(new StoryboardLayout(3, 3), StoryboardLayout.of(7));
        assertEquals(new StoryboardLayout(3, 3), StoryboardLayout.of(9));
        assertEquals(new StoryboardLayout(3, 4), StoryboardLayout.of(10));
        assertEquals(new StoryboardLayout(3, 4), StoryboardLayout.of(12));
        assertEquals(new StoryboardLayout(4, 4), StoryboardLayout.of(13));
        assertEquals(new StoryboardLayout(4, 4), StoryboardLayout.of(16));
        for (int panels = 1; panels <= 16; panels++) {
            StoryboardLayout layout = StoryboardLayout.of(panels);
            assertTrue(layout.cells() >= panels && layout.cells() - panels < layout.columns(), "panels=" + panels);
        }
        assertEquals("row 2, column 3", StoryboardLayout.of(12).position(7));
        assertThrows(IllegalArgumentException.class, () -> StoryboardLayout.of(0));
    }

    @Test
    void thePlanningPrompt_statesTheRule_theLimit_andEveryStepIdToCover() {
        StoryboardSource source = StoryboardTestFixtures.source(StoryboardTestFixtures.layeredRecipe());

        String system = builder.buildPlanSystemPrompt(12);
        assertTrue(system.contains("one panel = one visually meaningful cooking action or preparation outcome"));
        assertTrue(system.contains("Use at most 12 panels"));
        assertTrue(system.contains("sourceStepIds"));

        String user = builder.buildPlanUserPrompt(source);
        assertTrue(user.contains(source.json()));
        assertTrue(user.endsWith("p1, p2, p3, p4, p5, p6, p7, m1, m2, m3, m4, m5."), user);

        String retry = builder.buildPlanRetryPrompt(source, "{\"panels\":[]}", List.of("The storyboard has no panels"));
        assertTrue(retry.contains("- The storyboard has no panels"));
        assertTrue(retry.contains("{\"panels\":[]}"));
    }

    @Test
    void theSpecificationForTheSecondCall_carriesTheGrid_theStyle_andEveryPanelWithItsPosition() {
        RecipeStoryboard storyboard = StoryboardTestFixtures.storyboard(5);
        storyboard.getPanels().get(4).setReferencePanels(List.of("P2"));

        String user = builder.buildMasterUserPrompt(storyboard, StoryboardLayout.of(5), style);

        assertTrue(user.contains("\"grid\":{\"rows\":2,\"columns\":3,\"panels\":5}"), user);
        assertTrue(user.contains("\"lighting\":\"soft natural light\""), user);
        assertTrue(user.contains("\"position\":\"row 2, column 2\""), user);
        assertTrue(user.contains("\"referencePanels\":[\"P2\"]"), user);
        for (int panel = 1; panel <= 5; panel++) {
            assertTrue(user.contains("\"imagePrompt\":\"Photo of action " + panel + "\""), "panel " + panel);
        }
    }

    @Test
    void aMasterPromptMustNameEveryPanel_andNoMore() {
        assertTrue(builder.checkMasterPrompt("Panel 1 (row 1, column 1): a. Panel 2: b. PANEL 3: c.", 3).isEmpty());
        assertEquals(List.of("the prompt is empty"), builder.checkMasterPrompt("  ", 3));
        assertEquals(List.of("no line for panel 2, 3"), builder.checkMasterPrompt("Panel 1: a. Panel 12: b.", 3));
        assertEquals(List.of("it describes more than 2 panels"), builder.checkMasterPrompt("Panel 1, panel 2, panel 3", 2));
        // "Panel 1" is not found inside "Panel 10".
        assertFalse(builder.checkMasterPrompt("Panel 10: x", 1).isEmpty());
    }

    @Test
    void theTemplatePrompt_isOneImageOfAllPanelsInOrder_withTheSharedStyleAndTheRestrictions() {
        RecipeStoryboard storyboard = StoryboardTestFixtures.storyboard(7);
        StoryboardLayout layout = StoryboardLayout.of(7);

        String prompt = builder.buildTemplateMasterPrompt(storyboard, layout, style);

        assertTrue(builder.checkMasterPrompt(prompt, 7).isEmpty());
        assertTrue(prompt.startsWith("One image divided into a grid of 3 rows and 3 columns of equal panels, 7 panels in total"), prompt);
        assertTrue(prompt.contains("The 2 cells after the last panel stay plain white and empty."), prompt);
        int previous = -1;
        for (int panel = 1; panel <= 7; panel++) {
            String line = "Panel " + panel + " (" + layout.position(panel) + "): Photo of action " + panel + ". Result: State " + panel + ".";
            int at = prompt.indexOf(line);
            assertTrue(at > previous, "panel " + panel + " missing or out of order");
            previous = at;
        }
        assertTrue(prompt.contains("about 50 degrees above the counter looking down"));
        assertTrue(prompt.contains("No text, numbers, letters, labels, captions"));
        assertTrue(prompt.endsWith("Context: A North Indian home-style curry"));
    }
}
