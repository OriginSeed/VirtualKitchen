package com.processVisualisation.virtualKitchen.ai.platform.preference;

import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunModelChoicesTest {

    private final PreferredModelResolver resolver = mock(PreferredModelResolver.class);
    private final RunModelChoices choices = new RunModelChoices(resolver);

    @Test
    void theStartRunsWithThePickedModels_andTheyDontLeakPastIt() {
        Map<String, String> picked = Map.of("TEXT_TO_IMAGE", "better-image", "TEXT_TO_TEXT", "");
        when(resolver.validateRunChoices(picked)).thenReturn(Map.of("TEXT_TO_IMAGE", "better-image"));

        String seen = choices.startWith(picked, () -> AiRunModels.keyFor(AiCapability.TEXT_TO_IMAGE).orElse(null)
                + "/" + AiRunModels.keyFor(AiCapability.TEXT_TO_TEXT).orElse("account"));

        assertEquals("better-image/account", seen);
        assertTrue(AiRunModels.keyFor(AiCapability.TEXT_TO_IMAGE).isEmpty());
    }

    @Test
    void anUnavailableModelIsRejectedBeforeAnythingStarts() {
        Map<String, String> picked = Map.of("TEXT_TO_IMAGE", "nope");
        when(resolver.validateRunChoices(picked)).thenThrow(new IllegalArgumentException("not available"));

        assertThrows(IllegalArgumentException.class, () -> choices.startWith(picked, () -> {
            throw new AssertionError("must not start");
        }));
    }
}
