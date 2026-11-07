package com.processVisualisation.virtualKitchen.admin.config.models;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.provider.config.DeepInfraProperties;
import com.processVisualisation.virtualKitchen.ai.provider.config.GeminiProperties;
import com.processVisualisation.virtualKitchen.ai.provider.config.OpenAIProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Provider model lists for the add-model form, against mocked provider APIs (never the real ones). */
class ProviderModelCatalogTest {

    private static final String KEY = "sentinel-provider-key-31337";

    private MockRestServiceServer deepInfraServer;
    private MockRestServiceServer geminiServer;
    private MockRestServiceServer ollamaServer;
    private ProviderModelCatalog catalog;
    private DeepInfraProperties deepInfraProperties;

    @BeforeEach
    void setUp() {
        RestClient.Builder deepInfra = RestClient.builder().baseUrl("https://deepinfra.example");
        RestClient.Builder gemini = RestClient.builder().baseUrl("https://gemini.example");
        RestClient.Builder ollama = RestClient.builder().baseUrl("http://ollama.example");
        RestClient.Builder openAi = RestClient.builder().baseUrl("https://openai.example");
        MockRestServiceServer.bindTo(openAi).build();
        deepInfraServer = MockRestServiceServer.bindTo(deepInfra).build();
        geminiServer = MockRestServiceServer.bindTo(gemini).build();
        ollamaServer = MockRestServiceServer.bindTo(ollama).build();
        deepInfraProperties = new DeepInfraProperties();
        deepInfraProperties.setApiKey(KEY);
        GeminiProperties geminiProperties = new GeminiProperties();
        geminiProperties.setApiKey(KEY);
        catalog = new ProviderModelCatalog(deepInfra.build(), gemini.build(), openAi.build(), ollama.build(),
                deepInfraProperties, geminiProperties, new OpenAIProperties(), new ObjectMapper(), Clock.systemUTC());
    }

    @Test
    void deepInfraModelsAreFilteredByCapabilityAndCached() {
        deepInfraServer.expect(once(), requestTo("https://deepinfra.example/models/list"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY))
                .andRespond(withSuccess("""
                        [{"model_name": "deepseek-ai/DeepSeek-V4", "type": "text-generation"},
                         {"model_name": "black-forest-labs/FLUX-2-klein-4b", "type": "text-to-image"},
                         {"model_name": "hexgrad/Kokoro-82M", "type": "text-to-speech"}]
                        """, MediaType.APPLICATION_JSON));

        ProviderModelCatalog.Listing text = catalog.list("deepInfraAiClient", AiCapability.TEXT_TO_TEXT);
        ProviderModelCatalog.Listing again = catalog.list("deepInfraAiClient", AiCapability.TEXT_TO_TEXT);

        deepInfraServer.verify();
        assertNull(text.error());
        assertEquals(List.of("deepseek-ai/DeepSeek-V4"), text.models().stream().map(ProviderModelCatalog.Model::id).toList());
        assertEquals(text, again, "served from the cache: the server expected one call");
        assertFalse(text.toString().contains(KEY), "the key never appears in a result");
    }

    @Test
    void geminiListsOnlyGenerativeModelsOfTheCapability() {
        geminiServer.expect(once(), requestTo("https://gemini.example/v1beta/models?pageSize=1000"))
                .andExpect(header("x-goog-api-key", KEY))
                .andRespond(withSuccess("""
                        {"models": [
                          {"name": "models/gemini-3.5-flash", "displayName": "Gemini 3.5 Flash", "supportedGenerationMethods": ["generateContent"]},
                          {"name": "models/gemini-3.1-flash-image", "supportedGenerationMethods": ["generateContent"]},
                          {"name": "models/text-embedding-005", "supportedGenerationMethods": ["embedContent"]}]}
                        """, MediaType.APPLICATION_JSON));

        ProviderModelCatalog.Listing image = catalog.list("geminiImageClient", AiCapability.TEXT_TO_IMAGE);

        assertEquals(List.of("gemini-3.1-flash-image"), image.models().stream().map(ProviderModelCatalog.Model::id).toList());
    }

    @Test
    void ollamaListsItsLocalModels() {
        ollamaServer.expect(once(), requestTo("http://ollama.example/api/tags"))
                .andRespond(withSuccess("{\"models\": [{\"name\": \"qwen3:4b\"}, {\"name\": \"llama3.2:3b\"}]}", MediaType.APPLICATION_JSON));

        assertEquals(List.of("llama3.2:3b", "qwen3:4b"), catalog.list("ollamaAiClient", AiCapability.TEXT_TO_TEXT)
                .models().stream().map(ProviderModelCatalog.Model::id).toList());
    }

    @Test
    void anUnreachableProviderSaysSoInsteadOfAnEmptyListAndIsNotCached() {
        ollamaServer.expect(requestTo("http://ollama.example/api/tags")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        ProviderModelCatalog.Listing listing = catalog.list("ollamaAiClient", AiCapability.TEXT_TO_TEXT);

        assertNotNull(listing.error());
        assertTrue(listing.supported());
        assertTrue(listing.models().isEmpty());
    }

    @Test
    void withoutAKeyNothingIsCalled() {
        deepInfraProperties.setApiKey("");

        assertNotNull(catalog.list("deepInfraAiClient", AiCapability.TEXT_TO_TEXT).error());
        deepInfraServer.verify();
    }

    @Test
    void aProviderWithoutAListEndpointAsksForTyping() {
        ProviderModelCatalog.Listing listing = catalog.list("localTtsProvider", AiCapability.TEXT_TO_SPEECH);

        assertFalse(listing.supported());
        assertNotNull(listing.error());
    }
}
