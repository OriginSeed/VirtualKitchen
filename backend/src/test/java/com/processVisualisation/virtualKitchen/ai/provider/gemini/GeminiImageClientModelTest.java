package com.processVisualisation.virtualKitchen.ai.provider.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient.ImageGenerationSettings;
import com.processVisualisation.virtualKitchen.ai.provider.config.GeminiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;

import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The selected registry model is the Gemini image model that runs; without one, the configured model does. */
class GeminiImageClientModelTest {

    private static final String RESPONSE = """
            {"candidates": [{"content": {"parts": [{"inlineData": {"mimeType": "image/png", "data": "%s"}}]}}]}
            """.formatted(Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 'P', 'N', 'G'}));

    private GeminiProperties properties;
    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        properties = new GeminiProperties();
        properties.setApiKey("sentinel-gemini-key");
        properties.setChatEndpoint("/v1beta/models/{model}:generateContent");
        properties.setImageModel("configured-image-model");
        builder = RestClient.builder().baseUrl("https://gemini.example");
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    void theSelectedModelIsCalled() throws Exception {
        server.expect(once(), requestTo("https://gemini.example/v1beta/models/selected-image-model:generateContent"))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        client().generate("a kadai", List.of(), new ImageGenerationSettings(512, 512, null).withModel("selected-image-model"));

        server.verify();
    }

    @Test
    void withoutASelectedModelTheConfiguredOneIsCalled() throws Exception {
        server.expect(once(), requestTo("https://gemini.example/v1beta/models/configured-image-model:generateContent"))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        client().generate("a kadai");

        server.verify();
    }

    private GeminiImageClient client() {
        return new GeminiImageClient(builder.build(), properties, new ObjectMapper());
    }
}
