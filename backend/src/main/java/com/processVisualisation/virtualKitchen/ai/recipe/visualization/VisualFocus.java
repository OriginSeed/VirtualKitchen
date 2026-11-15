package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import java.util.Locale;
import java.util.Optional;

/**
 * What a focused step image centres on. The single place a focus is turned into framing: which piece of the
 * recipe's {@link SceneContext} fills the frame, and whether the recipe's scene anchor image (the empty cooking
 * vessel on the stove) is a useful reference for it.
 * <p>
 * A step's focus comes from its action's image purpose ({@link VisualizationImageProfiles#focusFor}); the prompt
 * writer may name another one when the step clearly happens elsewhere (e.g. mixing a batter in a bowl).
 */
public enum VisualFocus {

    /** Cutting, peeling, cleaning: the board, the knife and the ingredient. */
    CUTTING_BOARD(false),
    /** Adding, stirring, frying, boiling, simmering: the pan or pot on the stove. */
    COOKING_VESSEL(true),
    /** Mixing, marinating or coating away from the heat. */
    PREP_BOWL(false),
    /** Plating and serving. */
    PLATE(false),
    /** A multi-step preparation drawn as one storyboard image; never chosen by the prompt writer. */
    STORYBOARD(false);

    private final boolean usesSceneAnchor;

    VisualFocus(boolean usesSceneAnchor) {
        this.usesSceneAnchor = usesSceneAnchor;
    }

    /** Whether the scene anchor image (the empty vessel on the stove) shows this focus's subject. */
    public boolean usesSceneAnchor() {
        return usesSceneAnchor;
    }

    /** Whether consecutive images with this focus continue one another (a storyboard stands alone). */
    public boolean chains() {
        return this != STORYBOARD;
    }

    /** The sentence naming what fills the frame, in the recipe's own equipment. */
    public String framing(SceneContext scene) {
        return switch (this) {
            case CUTTING_BOARD -> capitalize(scene.cuttingBoard()) + " on " + scene.surface() + " fills most of the frame.";
            case COOKING_VESSEL -> capitalize(scene.vessel()) + " on " + scene.stove()
                    + " fills most of the frame, with a small strip of " + bare(scene.surface()) + " around it.";
            case PREP_BOWL -> capitalize(scene.prepBowl()) + " on " + scene.surface() + " fills most of the frame.";
            case PLATE -> capitalize(scene.servingDish()) + " on " + scene.surface() + " fills most of the frame.";
            case STORYBOARD -> "Every panel shows the same setup: " + scene.vessel() + " on " + scene.stove() + ", "
                    + scene.cuttingBoard() + " and " + scene.surface() + ".";
        };
    }

    /** A focus the prompt writer may choose, by name (case and separators ignored); empty for anything else. */
    public static Optional<VisualFocus> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String name = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (VisualFocus focus : values()) {
            if (focus != STORYBOARD && focus.name().equals(name)) {
                return Optional.of(focus);
            }
        }
        return Optional.empty();
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** "a grey stone countertop" -> "grey stone countertop", for use after "a strip of". */
    private static String bare(String text) {
        for (String article : new String[]{"a ", "an ", "the "}) {
            if (text.regionMatches(true, 0, article, 0, article.length())) {
                return text.substring(article.length());
            }
        }
        return text;
    }
}
