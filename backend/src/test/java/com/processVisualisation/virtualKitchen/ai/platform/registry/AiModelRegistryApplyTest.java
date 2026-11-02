package com.processVisualisation.virtualKitchen.ai.platform.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Admin runtime overrides on the model registry: applied atomically, validated, and never touching the baseline. */
class AiModelRegistryApplyTest {

    private AiModelProperties properties;
    private AiModelRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new AiModelProperties();
        properties.getModels().add(model("paid-text", AiCapability.TEXT_TO_TEXT, ModelTier.PAID, 2, "free-text"));
        properties.getModels().add(model("free-text", AiCapability.TEXT_TO_TEXT, ModelTier.OPEN_SOURCE, 0, null));
        properties.getModels().add(model("paid-image", AiCapability.TEXT_TO_IMAGE, ModelTier.PAID, 5, null));
        properties.getDefaultModel().put("TEXT_TO_TEXT", "paid-text");
        properties.getDefaultModel().put("TEXT_TO_IMAGE", "paid-image");
        registry = new AiModelRegistry(properties);
    }

    @Test
    void overridesApplyToTheLiveRegistryButNotTheBaseline() {
        registry.apply(Map.of("paid-text", m -> m.setCreditCost(7)), Map.of("TEXT_TO_TEXT", "free-text"));

        assertEquals(7, registry.find("paid-text").orElseThrow().getCreditCost());
        assertEquals("free-text", registry.defaultFor(AiCapability.TEXT_TO_TEXT).getKey());
        assertEquals(2, properties.getModels().get(0).getCreditCost(), "configured model is untouched");
        assertEquals(2, registry.baselineModels().get(0).getCreditCost());
    }

    @Test
    void applyingNothingRestoresTheBaseline() {
        registry.apply(Map.of("paid-text", m -> m.setCreditCost(7)), Map.of());
        registry.apply(Map.of(), Map.of());

        assertEquals(2, registry.find("paid-text").orElseThrow().getCreditCost());
    }

    @Test
    void aDisabledDefaultIsRejectedAndTheLiveRegistryIsUnchanged() {
        Map<String, Consumer<ModelDefinition>> disable = Map.of("paid-text", m -> m.setEnabled(false));

        assertThrows(IllegalArgumentException.class, () -> registry.apply(disable, Map.of()));
        assertTrue(registry.find("paid-text").orElseThrow().isEnabled());
    }

    @Test
    void aDefaultOfAnotherCapabilityIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.apply(Map.of(), Map.of("TEXT_TO_TEXT", "paid-image")));
    }

    @Test
    void aFallbackCycleIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.apply(Map.of("free-text", m -> m.setFallbackModelKey("paid-text")), Map.of()));
    }

    @Test
    void aFallbackOfAnotherCapabilityIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.apply(Map.of("paid-text", m -> m.setFallbackModelKey("paid-image")), Map.of()));
    }

    @Test
    void anUnknownModelIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.apply(Map.of("nope", m -> m.setCreditCost(1)), Map.of()));
    }

    @Test
    void userSelectableListsOnlyEnabledSelectableModels() {
        registry.apply(Map.of(
                "paid-text", m -> m.setUserSelectable(true),
                "free-text", m -> {
                    m.setUserSelectable(true);
                    m.setEnabled(false);
                }), Map.of());

        assertEquals(1, registry.userSelectable(AiCapability.TEXT_TO_TEXT).size());
        assertEquals("paid-text", registry.userSelectable(AiCapability.TEXT_TO_TEXT).get(0).getKey());
        assertFalse(registry.userSelectable(AiCapability.TEXT_TO_IMAGE).iterator().hasNext());
    }

    static ModelDefinition model(String key, AiCapability capability, ModelTier tier, int cost, String fallback) {
        ModelDefinition model = new ModelDefinition();
        model.setKey(key);
        model.setCapability(capability);
        model.setTier(tier);
        model.setCreditCost(cost);
        model.setFallbackModelKey(fallback);
        return model;
    }
}
