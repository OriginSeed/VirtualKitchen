package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import java.util.List;

/**
 * The storyboard planner's answer: the recipe told as panels. Node ids are the aliases of {@link StoryboardSource}
 * until {@link StoryboardSpecValidator} has checked them.
 */
public record StoryboardPlan(String title, String dishContext, List<Panel> panels, List<Omitted> omittedSteps) {

    /**
     * One panel: a visually meaningful cooking action or preparation outcome.
     *
     * @param group PREPARATION or MAIN
     * @param component the prepared component a preparation panel works towards
     * @param sourceStepIds the recipe nodes this panel stands for
     * @param dependsOn panels that must logically happen first
     * @param referencePanels earlier panels whose look reappears in this one
     */
    public record Panel(String panelId, Integer sequence, String group, String component, String description,
                        List<String> sourceStepIds, String imagePrompt, List<String> dependsOn,
                        List<String> referencePanels, List<String> ingredients, List<String> equipment,
                        String expectedState) {
    }

    /** A step that gets no panel of its own, and why. */
    public record Omitted(String stepId, String reason) {
    }
}
