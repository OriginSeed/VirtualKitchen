package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import com.processVisualisation.virtualKitchen.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 410: the per-process step-visuals path is switched off ({@code ai.storyboard.legacy-step-visuals-enabled}); step
 * images are made by Visualize (the recipe storyboard) instead.
 */
public class LegacyVisualsDisabledException extends ApiException {

    public LegacyVisualsDisabledException() {
        super("Step visuals are now made with Visualize for the whole recipe.", HttpStatus.GONE, null, Outcome.REJECTED);
    }
}
