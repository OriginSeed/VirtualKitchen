package com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model;

/** The stages of a storyboard generation, in order. */
public enum StoryboardStage {
    /** The text model groups the recipe's nodes into panels. */
    PLAN,
    /** The text model writes the one image prompt from the panels. */
    PROMPT,
    /** The image model draws the combined image. */
    IMAGE,
    /** The combined image is cut into panels and the panel images are put on the steps. */
    CROP
}
