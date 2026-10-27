package com.processVisualisation.virtualKitchen.ai.provider.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient.ReferenceImage;
import com.processVisualisation.virtualKitchen.ai.provider.config.GeminiProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiImageClientTest {

    private final GeminiImageClient client = new GeminiImageClient(null, new GeminiProperties(), new ObjectMapper());

    @Test
    void referenceImagesGoAheadOfThePromptAsInlineData() {
        Map<String, Object> payload = client.buildRequestPayload("draw step 2",
                List.of(new ReferenceImage("image/png", new byte[]{1, 2, 3})));

        List<Map<String, Object>> parts = parts(payload);
        assertEquals(2, parts.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> inline = (Map<String, Object>) parts.get(0).get("inlineData");
        assertEquals("image/png", inline.get("mimeType"));
        assertEquals(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}), inline.get("data"));
        assertEquals("draw step 2", parts.get(1).get("text"));
    }

    @Test
    void withoutReferences_thePayloadIsTheTextAlone() {
        List<Map<String, Object>> parts = parts(client.buildRequestPayload("draw step 1", List.of()));

        assertEquals(List.of(Map.of("text", "draw step 1")), parts);
    }

    @Test
    void geminiTakesReferenceImages() {
        assertTrue(client.supportsReferenceImages());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parts(Map<String, Object> payload) {
        List<Map<String, Object>> contents = (List<Map<String, Object>>) payload.get("contents");
        return (List<Map<String, Object>>) contents.get(0).get("parts");
    }
}
