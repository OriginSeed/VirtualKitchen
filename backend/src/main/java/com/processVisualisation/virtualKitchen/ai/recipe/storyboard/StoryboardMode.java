package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

/** How a new Visualize run treats what the recipe already has. */
public enum StoryboardMode {

    /**
     * Spend only what is needed. When the latest storyboard still matches the recipe, no AI is called: its panels are
     * cut again if needed and put back on the steps that lost them. Otherwise a new storyboard is made (continuing a
     * failed attempt where it stopped), and images the steps already have of their own are kept.
     */
    FILL,

    /** Plan, prompt and draw a new storyboard, and replace every step image it covers. */
    REPLACE
}
