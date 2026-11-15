package com.processVisualisation.virtualKitchen.admin.config.sections;

import com.processVisualisation.virtualKitchen.admin.config.ConfigField;
import com.processVisualisation.virtualKitchen.ai.provider.ImageStorageClient;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationImageProperties;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationSceneAnchorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ImageProfilesConfigSectionTest {

    private VisualizationSceneAnchorService sceneAnchors;
    private ImageProfilesConfigSection section;
    private VisualizationImageProperties properties;

    @BeforeEach
    void setUp() {
        sceneAnchors = new VisualizationSceneAnchorService(
                mock(RecipeProcessVisualizationService.class), mock(ImageStorageClient.class), true);
        properties = new VisualizationImageProperties();
        section = new ImageProfilesConfigSection(properties, sceneAnchors);
    }

    @Test
    void theFocusedView_andItsReferenceChain_areOnByDefaultAndSwitchableAtRuntime() {
        assertTrue(properties.isFocusedViewEnabled());
        assertTrue(properties.isReferenceChainEnabled());

        section.apply(Map.of(ImageProfilesConfigSection.FOCUSED_VIEW_ENABLED, "false"));
        assertFalse(properties.isFocusedViewEnabled(), "off = images are made as before the focused view");
        assertTrue(properties.isReferenceChainEnabled());
        assertTrue(sceneAnchors.isEnabled(), "the scene anchor is a separate switch");

        section.apply(Map.of(ImageProfilesConfigSection.REFERENCE_CHAIN_ENABLED, "false"));
        assertTrue(properties.isFocusedViewEnabled(), "no override = back to the configured value");
        assertFalse(properties.isReferenceChainEnabled());

        section.apply(Map.of());
        assertTrue(properties.isFocusedViewEnabled());
        assertTrue(properties.isReferenceChainEnabled());
    }

    @Test
    void theFocusedViewToggles_followTheConfiguredValues() {
        VisualizationImageProperties configuredOff = new VisualizationImageProperties();
        configuredOff.setFocusedViewEnabled(false);
        ImageProfilesConfigSection offByConfig = new ImageProfilesConfigSection(configuredOff, sceneAnchors);

        ConfigField field = offByConfig.fields(Map.of()).stream()
                .filter(f -> f.key().equals(ImageProfilesConfigSection.FOCUSED_VIEW_ENABLED)).findFirst().orElseThrow();
        assertEquals(ConfigField.Type.TOGGLE, field.type());
        assertEquals("false", field.baseline());

        offByConfig.apply(Map.of(ImageProfilesConfigSection.FOCUSED_VIEW_ENABLED, "true"));
        assertTrue(configuredOff.isFocusedViewEnabled());
        offByConfig.apply(Map.of());
        assertFalse(configuredOff.isFocusedViewEnabled());
    }

    @Test
    void sceneAnchorToggle_showsTheConfiguredValue() {
        ConfigField field = field(Map.of());

        assertEquals(ConfigField.Type.TOGGLE, field.type());
        assertEquals("true", field.baseline());
        assertEquals("true", field.current());
        assertFalse(field.overridden());
    }

    @Test
    void overridingTheSceneAnchor_switchesTheLiveService_andRemovingTheOverrideRestoresIt() {
        section.apply(Map.of(ImageProfilesConfigSection.SCENE_ANCHOR_ENABLED, "false"));
        assertFalse(sceneAnchors.isEnabled());
        ConfigField field = field(Map.of(ImageProfilesConfigSection.SCENE_ANCHOR_ENABLED, "false"));
        assertEquals("false", field.current());
        assertTrue(field.overridden());

        section.apply(Map.of());
        assertTrue(sceneAnchors.isEnabled(), "no override = back to ai.visualization.scene-anchor.enabled");
    }

    @Test
    void anUnknownKey_isRejected_andLeavesTheSceneAnchorAlone() {
        assertThrows(IllegalArgumentException.class, () -> section.apply(Map.of(
                ImageProfilesConfigSection.SCENE_ANCHOR_ENABLED, "false", "nope", "1")));
        assertTrue(sceneAnchors.isEnabled());
    }

    @Test
    void preparationStepsPerImage_offersOneTwoOrFour_andRejectsAnythingElse() {
        ConfigField field = section.fields(Map.of()).stream()
                .filter(f -> f.key().equals(ImageProfilesConfigSection.PREPARATION_STEPS_PER_IMAGE)).findFirst().orElseThrow();
        assertEquals(ConfigField.Type.SELECT, field.type());
        assertEquals(java.util.List.of("1", "2", "4"), field.options());
        assertEquals("4", field.baseline());

        section.apply(Map.of(ImageProfilesConfigSection.PREPARATION_STEPS_PER_IMAGE, "2"));
        assertEquals(2, properties.getPreparationStepsPerImage());
        assertThrows(IllegalArgumentException.class,
                () -> section.apply(Map.of(ImageProfilesConfigSection.PREPARATION_STEPS_PER_IMAGE, "3")));
        assertEquals(2, properties.getPreparationStepsPerImage(), "a rejected value changes nothing");

        section.apply(Map.of());
        assertEquals(4, properties.getPreparationStepsPerImage(), "no override = back to the configured value");
    }

    @Test
    void skipLookAlikeSteps_isOnByDefaultAndSwitchableAtRuntime() {
        assertTrue(properties.isSkipLookAlikeSteps());
        section.apply(Map.of(ImageProfilesConfigSection.SKIP_LOOK_ALIKE_STEPS, "false"));
        assertFalse(properties.isSkipLookAlikeSteps());
        section.apply(Map.of());
        assertTrue(properties.isSkipLookAlikeSteps());
    }

    private ConfigField field(Map<String, String> overrides) {
        return section.fields(overrides).stream()
                .filter(f -> f.key().equals(ImageProfilesConfigSection.SCENE_ANCHOR_ENABLED))
                .findFirst().orElseThrow();
    }
}
