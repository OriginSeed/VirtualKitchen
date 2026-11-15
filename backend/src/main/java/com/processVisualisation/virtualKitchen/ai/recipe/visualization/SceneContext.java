package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import org.springframework.util.StringUtils;

/**
 * The equipment every image of one recipe is drawn with: chosen once per recipe by the prompt writer, stored on the
 * recipe's scene asset, and written into every step image's prompt by code, so the pan, stove and counter are
 * described in the same words in every image. Each value is a short noun phrase with its article
 * ("a black round frying pan").
 * <p>
 * Camera, lighting and style are not per recipe: they are the configured
 * {@link VisualizationImageProperties.Scene} defaults.
 *
 * @param vessel the main pan or pot of the main cooking steps
 * @param stove the stove or cooking surface the vessel sits on
 * @param surface the countertop
 * @param cuttingBoard the board ingredients are cut on
 * @param prepBowl the bowl used for mixing or marinating away from the heat
 * @param servingDish the plate or bowl the dish is served in
 */
public record SceneContext(String vessel, String stove, String surface, String cuttingBoard, String prepBowl,
                           String servingDish) {

    /** This context with every blank value taken from {@code defaults}; all of them when {@code scene} is null. */
    public static SceneContext orDefaults(SceneContext scene, SceneContext defaults) {
        if (scene == null) {
            return defaults;
        }
        return new SceneContext(
                first(scene.vessel(), defaults.vessel()),
                first(scene.stove(), defaults.stove()),
                first(scene.surface(), defaults.surface()),
                first(scene.cuttingBoard(), defaults.cuttingBoard()),
                first(scene.prepBowl(), defaults.prepBowl()),
                first(scene.servingDish(), defaults.servingDish()));
    }

    private static String first(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
