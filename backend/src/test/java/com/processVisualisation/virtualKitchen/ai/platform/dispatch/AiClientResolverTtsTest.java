package com.processVisualisation.virtualKitchen.ai.platform.dispatch;

import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiCommunicationException;
import com.processVisualisation.virtualKitchen.ai.provider.tts.AudioFormat;
import com.processVisualisation.virtualKitchen.ai.provider.tts.GeneratedAudio;
import com.processVisualisation.virtualKitchen.ai.provider.tts.TtsProvider;
import com.processVisualisation.virtualKitchen.ai.provider.tts.TtsRequest;
import com.processVisualisation.virtualKitchen.common.logging.LogCapture;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiClientResolverTtsTest {

    private static final String SECRET_TEXT = "sentinel-narration-text-31337";

    private final TtsProvider gemini = provider("gemini", false);
    private final TtsProvider local = provider("local", false);
    private final AiClientResolver resolver = new AiClientResolver(
            Map.of(), Map.of(), Map.of("geminiTtsProvider", gemini, "localTtsProvider", local, "failingTtsProvider", provider("x", true),
                    "deepInfraTtsProvider", provider("deepinfra", false)));

    @Test
    void resolvesTheProviderBeanTheModelNames() {
        assertEquals("gemini", resolver.resolveTtsProvider(model("gemini-tts", "geminiTtsProvider")).providerName());
        assertEquals("local", resolver.resolveTtsProvider(model("local-tts", "localTtsProvider")).providerName());
        assertEquals("deepinfra", resolver.resolveTtsProvider(model("deepinfra-mimo-tts", "deepInfraTtsProvider")).providerName());
    }

    @Test
    void unknownProviderBeanIsAConfigurationError() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> resolver.resolveTtsProvider(model("azure-tts", "azureTtsProvider")));
        assertTrue(ex.getMessage().contains("azureTtsProvider"));
    }

    @Test
    void logsEachSynthesisWithoutTheText() {
        try (LogCapture logs = LogCapture.of(LoggingTtsProvider.class)) {
            resolver.resolveTtsProvider(model("gemini-tts", "geminiTtsProvider"))
                    .synthesize(new TtsRequest(SECRET_TEXT, "m", "v", null, null));
            assertThrows(AiCommunicationException.class, () -> resolver.resolveTtsProvider(model("bad", "failingTtsProvider"))
                    .synthesize(new TtsRequest(SECRET_TEXT, "m", "v", null, null)));

            assertEquals(1, logs.events("tts_synthesis_completed").size());
            assertEquals(1, logs.events("tts_synthesis_failed").size());
            assertFalse(logs.allMessages().contains(SECRET_TEXT), logs.allMessages());
        }
    }

    private static TtsProvider provider(String name, boolean fail) {
        return new TtsProvider() {
            @Override
            public String providerName() {
                return name;
            }

            @Override
            public GeneratedAudio synthesize(TtsRequest request) {
                if (fail) {
                    throw new AiCommunicationException("down");
                }
                return new GeneratedAudio(new byte[]{1}, "audio/wav", AudioFormat.WAV, 10L, "v", request.providerModelId());
            }
        };
    }

    private static ModelDefinition model(String key, String bean) {
        ModelDefinition model = new ModelDefinition();
        model.setKey(key);
        model.setCapability(AiCapability.TEXT_TO_SPEECH);
        model.setProviderBean(bean);
        return model;
    }
}
