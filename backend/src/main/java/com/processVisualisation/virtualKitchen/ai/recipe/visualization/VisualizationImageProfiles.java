package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient.ImageGenerationSettings;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider;
import org.springframework.stereotype.Component;

/**
 * Chooses a step image's generation settings from what the step does: action → purpose (per-action override, else
 * the action's catalog category) → that purpose's profile from {@link VisualizationImageProperties}. Provider-neutral:
 * each image client decides which of the resulting settings its model supports.
 */
@Component
public class VisualizationImageProfiles {

    /** The purpose of an action that has none (unknown, {@code custom}, or a category without a mapping). */
    public static final String DEFAULT_PURPOSE = "default";

    private final VisualizationImageProperties properties;
    private final RecipeStepVocabularyProvider vocabularyProvider;

    public VisualizationImageProfiles(VisualizationImageProperties properties, RecipeStepVocabularyProvider vocabularyProvider) {
        this.properties = properties;
        this.vocabularyProvider = vocabularyProvider;
    }

    /** The image purpose of a step action id, or {@link #DEFAULT_PURPOSE}. */
    public String purposeOf(String actionId) {
        if (actionId == null) {
            return DEFAULT_PURPOSE;
        }
        String override = properties.getPurposeByAction().get(actionId);
        if (override != null) {
            return override;
        }
        return vocabularyProvider.action(actionId)
                .map(action -> properties.getPurposeByCategory().get(action.category()))
                .orElse(DEFAULT_PURPOSE);
    }

    /**
     * The settings for a step action's image: its purpose's profile, with unset fields taken from the default
     * profile. {@link ImageGenerationSettings#NONE} when neither sets anything, so providers keep their own settings.
     */
    public ImageGenerationSettings settingsFor(String actionId) {
        VisualizationImageProperties.Profile fallback = properties.getDefaultProfile();
        VisualizationImageProperties.Profile profile = properties.getProfiles().get(purposeOf(actionId));
        if (profile == null) {
            profile = fallback;
        }
        ImageGenerationSettings settings = new ImageGenerationSettings(
                firstSet(profile.getWidth(), fallback.getWidth()),
                firstSet(profile.getHeight(), fallback.getHeight()),
                firstSet(profile.getSteps(), fallback.getSteps()));
        return settings.isNone() ? ImageGenerationSettings.NONE : settings;
    }

    private static Integer firstSet(Integer value, Integer fallback) {
        return value != null ? value : fallback;
    }
}
