package com.processVisualisation.virtualKitchen.ai.platform.dispatch;

import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient.GeneratedImage;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient.ReferenceImage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingImageGenerationClientTest {

    /** A provider that records what it was called with; it takes references only when told to. */
    private static final class RecordingClient implements ImageGenerationClient {
        private final boolean takesReferences;
        final List<String> calls = new ArrayList<>();

        RecordingClient(boolean takesReferences) {
            this.takesReferences = takesReferences;
        }

        @Override
        public GeneratedImage generate(String prompt) {
            calls.add("text:" + prompt);
            return new GeneratedImage("image/png", new byte[]{1});
        }

        @Override
        public GeneratedImage generate(String prompt, List<ReferenceImage> references) throws com.fasterxml.jackson.core.JsonProcessingException {
            if (!takesReferences) return ImageGenerationClient.super.generate(prompt, references);
            calls.add("refs:" + references.size() + ":" + prompt);
            return new GeneratedImage("image/png", new byte[]{1});
        }

        @Override
        public GeneratedImage generate(String prompt, List<ReferenceImage> references,
                                       ImageGenerationClient.ImageGenerationSettings settings) {
            calls.add("settings:" + settings.width() + "x" + settings.height() + ":" + settings.steps() + ":" + prompt);
            return new GeneratedImage("image/png", new byte[]{1});
        }

        @Override
        public boolean supportsReferenceImages() {
            return takesReferences;
        }
    }

    @Test
    void settingsAreForwardedToTheProvider() throws Exception {
        RecordingClient provider = new RecordingClient(false);

        new LoggingImageGenerationClient(provider, "deepinfra", "flux").generate("step", List.of(),
                new ImageGenerationClient.ImageGenerationSettings(512, 256, null));

        assertEquals(List.of("settings:512x256:null:step"), provider.calls);
    }

    @Test
    void noSettings_keepsThePlainRouting() throws Exception {
        RecordingClient provider = new RecordingClient(true);
        LoggingImageGenerationClient client = new LoggingImageGenerationClient(provider, "gemini", "image-model");

        client.generate("a", List.of(), ImageGenerationClient.ImageGenerationSettings.NONE);
        client.generate("b", List.of(SCENE), ImageGenerationClient.ImageGenerationSettings.NONE);
        client.generate("c", List.of(), null);

        assertEquals(List.of("text:a", "refs:1:b", "text:c"), provider.calls);
    }

    private static final ReferenceImage SCENE = new ReferenceImage("image/png", new byte[]{9});

    @Test
    void referencesReachAProviderThatTakesThem() throws Exception {
        RecordingClient provider = new RecordingClient(true);
        LoggingImageGenerationClient client = new LoggingImageGenerationClient(provider, "gemini", "image-model");

        client.generate("step", List.of(SCENE));

        assertEquals(List.of("refs:1:step"), provider.calls);
        assertTrue(client.supportsReferenceImages());
    }

    @Test
    void noReferences_isAPlainTextCall() throws Exception {
        RecordingClient provider = new RecordingClient(true);

        new LoggingImageGenerationClient(provider, "gemini", "image-model").generate("step", List.of());

        assertEquals(List.of("text:step"), provider.calls);
    }

    @Test
    void aProviderWithoutImageInput_ignoresReferences() throws Exception {
        RecordingClient provider = new RecordingClient(false);
        LoggingImageGenerationClient client = new LoggingImageGenerationClient(provider, "drawthings", "sdxl");

        client.generate("step", List.of(SCENE));

        assertEquals(List.of("text:step"), provider.calls);
        assertFalse(client.supportsReferenceImages());
    }
}
