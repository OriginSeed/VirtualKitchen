package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import com.processVisualisation.virtualKitchen.common.web.ApiException;
import org.springframework.http.HttpStatus;

/** A storyboard request that cannot be carried out as the recipe stands. */
public class RecipeStoryboardException extends ApiException {

    public RecipeStoryboardException(HttpStatus status, String message) {
        super(message, status, null, Outcome.REJECTED);
    }

    /** 409: other work on the same recipe has to finish first. */
    public static RecipeStoryboardException conflict(String message) {
        return new RecipeStoryboardException(HttpStatus.CONFLICT, message);
    }

    /** 422: the recipe is not in a state a storyboard can be made from (no steps, too long). */
    public static RecipeStoryboardException unprocessable(String message) {
        return new RecipeStoryboardException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
