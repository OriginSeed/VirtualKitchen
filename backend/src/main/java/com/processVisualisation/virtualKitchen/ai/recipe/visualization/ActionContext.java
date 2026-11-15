package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import org.springframework.util.StringUtils;

/**
 * What changes from one step image to the next: where the camera looks, what is in the frame, and what is being done.
 * Everything that stays the same lives in {@link SceneContext}.
 *
 * @param focus what the image centres on
 * @param state what is visible in the frame at this step, including what earlier steps left there
 * @param motion what is being done in this step, with which utensil
 */
public record ActionContext(VisualFocus focus, String state, String motion) {

    /** The step's own text of a focused image prompt: the state, then the action. Blank when both are. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        if (StringUtils.hasText(state)) text.append(sentence(state));
        if (StringUtils.hasText(motion)) {
            if (text.length() > 0) text.append(' ');
            text.append(sentence(motion));
        }
        return text.toString();
    }

    private static String sentence(String value) {
        String trimmed = value.trim();
        return trimmed.endsWith(".") || trimmed.endsWith("!") ? trimmed : trimmed + ".";
    }
}
