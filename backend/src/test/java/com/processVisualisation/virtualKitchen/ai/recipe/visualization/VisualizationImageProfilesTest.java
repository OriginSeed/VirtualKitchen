package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient.ImageGenerationSettings;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;

import static com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationImageProperties.COOKING_PROCESS;
import static com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationImageProperties.INGREDIENT_ADDITION;
import static com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationImageProperties.INGREDIENT_PREPARATION;
import static com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationImageProperties.MIXING;
import static com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationImageProperties.SERVING;
import static com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationImageProperties.WAITING_RESTING;
import static com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationImageProfiles.DEFAULT_PURPOSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Action → purpose → profile, against the real step catalog. */
class VisualizationImageProfilesTest {

    private static final RecipeStepVocabularyProvider VOCABULARY = new RecipeStepVocabularyProvider();

    @Test
    void actionsMapToTheirPurposeThroughTheirCatalogCategory() {
        VisualizationImageProfiles profiles = profiles(new VisualizationImageProperties());

        assertEquals(INGREDIENT_PREPARATION, profiles.purposeOf("chop"));
        assertEquals(INGREDIENT_PREPARATION, profiles.purposeOf("peel"));
        assertEquals(INGREDIENT_ADDITION, profiles.purposeOf("add"));
        assertEquals(INGREDIENT_ADDITION, profiles.purposeOf("pour"));
        assertEquals(COOKING_PROCESS, profiles.purposeOf("boil"));
        assertEquals(COOKING_PROCESS, profiles.purposeOf("bake"));
        assertEquals(MIXING, profiles.purposeOf("whisk"));
        assertEquals(WAITING_RESTING, profiles.purposeOf("rest"));
        assertEquals(WAITING_RESTING, profiles.purposeOf("wait"));
        assertEquals(SERVING, profiles.purposeOf("serve"));
        assertEquals(SERVING, profiles.purposeOf("garnish"));
    }

    @Test
    void everyCatalogActionExceptCustomHasAPurpose() {
        VisualizationImageProfiles profiles = profiles(new VisualizationImageProperties());

        List<String> unclassified = java.util.Arrays.stream(VOCABULARY.actionIds().split("\\|"))
                .filter(id -> !"custom".equals(id))
                .filter(id -> DEFAULT_PURPOSE.equals(profiles.purposeOf(id)))
                .toList();
        assertEquals(List.of(), unclassified);
    }

    @Test
    void anActionOverrideWinsOverItsCategory() {
        VisualizationImageProperties properties = new VisualizationImageProperties();
        properties.getPurposeByAction().put("reduce", WAITING_RESTING);
        VisualizationImageProfiles profiles = profiles(properties);

        assertEquals(WAITING_RESTING, profiles.purposeOf("reduce"));
        assertEquals(COOKING_PROCESS, profiles.purposeOf("flip"), "built-in override");
        assertEquals(COOKING_PROCESS, profiles.purposeOf("simmer"), "rest of the category unchanged");
    }

    @Test
    void customUnknownAndMissingActionsUseTheDefaultPurpose() {
        VisualizationImageProfiles profiles = profiles(new VisualizationImageProperties());

        assertEquals(DEFAULT_PURPOSE, profiles.purposeOf("custom"));
        assertEquals(DEFAULT_PURPOSE, profiles.purposeOf("levitate"));
        assertEquals(DEFAULT_PURPOSE, profiles.purposeOf(null));
    }

    @Test
    void thePurposeProfileIsSelected() {
        VisualizationImageProfiles profiles = profiles(configured());

        assertEquals(new ImageGenerationSettings(768, 768, 8), profiles.settingsFor("serve"));
        assertEquals(new ImageGenerationSettings(256, 256, 4), profiles.settingsFor("rest"));
    }

    @Test
    void unknownActionAndAPurposeWithoutAProfileUseTheDefaultProfile() {
        VisualizationImageProfiles profiles = profiles(configured());
        ImageGenerationSettings defaults = new ImageGenerationSettings(512, 512, 4);

        assertEquals(defaults, profiles.settingsFor("levitate"));
        assertEquals(defaults, profiles.settingsFor("custom"));
        assertEquals(defaults, profiles.settingsFor("whisk"), "mixing has no profile here");
    }

    @Test
    void unsetProfileFieldsFallBackToTheDefaultProfile() {
        VisualizationImageProperties properties = configured();
        VisualizationImageProperties.Profile widthOnly = new VisualizationImageProperties.Profile();
        widthOnly.setWidth(640);
        properties.getProfiles().put(INGREDIENT_PREPARATION, widthOnly);

        assertEquals(new ImageGenerationSettings(640, 512, 4), profiles(properties).settingsFor("chop"));
    }

    @Test
    void nothingConfiguredMeansNoOverrides() {
        assertSame(ImageGenerationSettings.NONE, profiles(new VisualizationImageProperties()).settingsFor("serve"));
    }

    @Test
    void configuredMappingsMergeOverTheBuiltInDefaults() {
        VisualizationImageProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "ai.visualization.image.purpose-by-category.sauce", MIXING,
                "ai.visualization.image.purpose-by-action.reduce", WAITING_RESTING,
                "ai.visualization.image.default-profile.width", "512",
                "ai.visualization.image.profiles.serving.width", "768",
                "ai.visualization.image.profiles.serving.steps", "6")))
                .bind("ai.visualization.image", VisualizationImageProperties.class).get();
        VisualizationImageProfiles profiles = profiles(properties);

        assertEquals(MIXING, profiles.purposeOf("strain"), "configured category mapping");
        assertEquals(WAITING_RESTING, profiles.purposeOf("reduce"), "configured action override");
        assertEquals(COOKING_PROCESS, profiles.purposeOf("flip"), "built-in action override kept");
        assertEquals(INGREDIENT_PREPARATION, profiles.purposeOf("chop"), "built-in category mapping kept");
        assertEquals(new ImageGenerationSettings(768, null, 6), profiles.settingsFor("serve"));
        assertEquals(new ImageGenerationSettings(512, null, null), profiles.settingsFor("chop"));
    }

    @Test
    void aFlowImageUsesItsLastStepsAction() {
        RecipeProcessVisualizationInput chop = input("chop");
        RecipeProcessVisualizationInput mix = input("mix");

        assertEquals("mix", RecipeProcessVisualizationService.profileActionId(new RecipeProcessVisualizationService.VisualTarget(
                "out", null, new RecipeProcessVisualizationService.FlowContext("Batter", List.of(chop, mix)))));
        assertEquals("chop", RecipeProcessVisualizationService.profileActionId(
                new RecipeProcessVisualizationService.VisualTarget("s1", chop, null)));
        assertNotEquals("chop", RecipeProcessVisualizationService.profileActionId(
                new RecipeProcessVisualizationService.VisualTarget("s1", input(null), null)));
    }

    private static VisualizationImageProperties configured() {
        VisualizationImageProperties properties = new VisualizationImageProperties();
        properties.setDefaultProfile(profile(512, 512, 4));
        properties.getProfiles().put(SERVING, profile(768, 768, 8));
        properties.getProfiles().put(WAITING_RESTING, profile(256, 256, null));
        return properties;
    }

    private static VisualizationImageProperties.Profile profile(Integer width, Integer height, Integer steps) {
        VisualizationImageProperties.Profile profile = new VisualizationImageProperties.Profile();
        profile.setWidth(width);
        profile.setHeight(height);
        profile.setSteps(steps);
        return profile;
    }

    private static RecipeProcessVisualizationInput input(String actionId) {
        return new RecipeProcessVisualizationInput("Step", List.of(), List.of(), "", "", "", "", "", null, actionId);
    }

    private static VisualizationImageProfiles profiles(VisualizationImageProperties properties) {
        return new VisualizationImageProfiles(properties, VOCABULARY);
    }
}
