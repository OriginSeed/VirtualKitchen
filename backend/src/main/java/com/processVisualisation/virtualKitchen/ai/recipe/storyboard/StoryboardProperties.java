package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Limits and image size of the recipe storyboard ({@code ai.storyboard.*}). */
@Data
@Component
@ConfigurationProperties(prefix = "ai.storyboard")
public class StoryboardProperties {

    /** Hard upper bound of {@link #maxPanels}: a 4x4 grid. */
    public static final int PANEL_LIMIT = 16;

    /** Most panels one storyboard has; the planner merges actions to stay within it. */
    private int maxPanels = PANEL_LIMIT;

    /** A recipe with more steps and checks than this is refused: it cannot be told in one image. */
    private int maxSourceSteps = 80;

    /** Requested size of the combined image (providers that choose their own size ignore it). */
    private int imageWidth = 1024;

    private int imageHeight = 1024;

    /** {@link #maxPanels} kept within 1..{@link #PANEL_LIMIT}. */
    public int effectiveMaxPanels() {
        return Math.max(1, Math.min(PANEL_LIMIT, maxPanels));
    }
}
