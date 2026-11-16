package com.processVisualisation.virtualKitchen.ai.recipe.storyboard.dto;

import lombok.Data;

import java.util.Map;

/** Optional body of "generate storyboard". */
@Data
public class StoryboardJobRequestDTO {

    /** The models picked for this run, by capability (TEXT_TO_TEXT, TEXT_TO_IMAGE); the account setting otherwise. */
    private Map<String, String> modelKeys;

    /**
     * Plan the panels again even when the last attempt failed on the same recipe (which is otherwise continued from
     * the stage it failed at).
     */
    private boolean regenerate;

    /** Replace images the covered steps already have (otherwise only steps without an image get a panel image). */
    private boolean overwriteExisting;
}
