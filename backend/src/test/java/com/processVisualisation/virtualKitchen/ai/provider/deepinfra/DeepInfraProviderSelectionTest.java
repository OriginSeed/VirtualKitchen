package com.processVisualisation.virtualKitchen.ai.provider.deepinfra;

import com.processVisualisation.virtualKitchen.ai.platform.analytics.pricing.AnalyticsPricingProperties;
import com.processVisualisation.virtualKitchen.ai.platform.dispatch.AiClientResolver;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelProperties;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelRegistry;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelTier;
import com.processVisualisation.virtualKitchen.ai.provider.AiClient;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient.GeneratedImage;
import com.processVisualisation.virtualKitchen.ai.provider.config.DeepInfraProperties;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiRequest;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider selection is configuration only: the documented registry entries, with the documented environment
 * variables layered on top, decides whether text and (independently) image generation route to DeepInfra.
 * No Spring context, no HTTP.
 */
class DeepInfraProviderSelectionTest {

    private static final String KEY = "deepinfra-deepseek-v4-flash";
    private static final String IMAGE_KEY = "deepinfra-flux2-klein";

    @Test
    void deepInfraIsDisabledAndNotTheDefaultOutOfTheBox() {
        Binder binder = binder(Map.of());
        AiModelRegistry registry = new AiModelRegistry(binder.bind("ai", AiModelProperties.class).get());

        assertEquals("gemini-flash", registry.defaultFor(AiCapability.TEXT_TO_TEXT).getKey());
        ModelDefinition deepInfra = registry.find(KEY).orElseThrow();
        assertFalse(deepInfra.isEnabled());
        assertTrue(binder.bind("ai.deepinfra.api-key", String.class).orElse("").isBlank(),
                "no API key may ship in source control");
    }

    @Test
    void environmentVariablesSwitchTextGenerationToDeepInfra() {
        Binder binder = binder(Map.of(
                "DEEPINFRA_ENABLED", "true",
                "AI_TEXT_DEFAULT_MODEL", KEY,
                "DEEPINFRA_API_KEY", "env-supplied-key"));
        AiModelRegistry registry = new AiModelRegistry(binder.bind("ai", AiModelProperties.class).get());
        DeepInfraProperties properties = binder.bind("ai.deepinfra", DeepInfraProperties.class).get();

        ModelDefinition model = registry.defaultFor(AiCapability.TEXT_TO_TEXT);
        assertEquals(KEY, model.getKey());
        assertEquals("deepInfraAiClient", model.getProviderBean());
        assertEquals(ModelTier.PAID, model.getTier());
        assertEquals("deepseek-ai/DeepSeek-V4-Flash", model.getProviderModelId());
        assertEquals(0.20, model.getInputUsdPerMillion());
        assertEquals(0.006, model.getCachedInputUsdPerMillion());
        assertEquals(0.60, model.getOutputUsdPerMillion());
        assertEquals("ollama-qwen", registry.fallbackFor(model).orElseThrow().getKey());

        assertEquals("env-supplied-key", properties.getApiKey());
        assertEquals("https://api.deepinfra.com", properties.getBaseUrl());
        assertEquals("/v1/openai/chat/completions", properties.getChatEndpoint());
        assertEquals("deepseek-ai/DeepSeek-V4-Flash", properties.getDefaultModel());
        assertEquals("none", properties.getReasoningEffort());
        assertTrue(properties.isJsonSchemaEnabled());
    }

    @Test
    void selectingDeepInfraWhileItIsDisabledIsAConfigurationError() {
        AiModelRegistry registry = new AiModelRegistry(
                binder(Map.of("AI_TEXT_DEFAULT_MODEL", KEY)).bind("ai", AiModelProperties.class).get());

        assertThrows(IllegalStateException.class, () -> registry.defaultFor(AiCapability.TEXT_TO_TEXT));
    }

    @Test
    void resolverRoutesTheDeepInfraModelToTheDeepInfraBean() {
        AtomicReference<AiRequest> deepInfraCall = new AtomicReference<>();
        AiClient deepInfra = request -> {
            deepInfraCall.set(request);
            return AiResponse.builder().content("ok").build();
        };
        AiClient gemini = request -> {
            throw new AssertionError("gemini must not be called");
        };
        AiClientResolver resolver = new AiClientResolver(
                Map.of("deepInfraAiClient", deepInfra, "geminiAiClient", gemini), Map.of());
        ModelDefinition model = new ModelDefinition();
        model.setKey(KEY);
        model.setProviderBean("deepInfraAiClient");

        AiRequest request = AiRequest.builder().userPrompt("recipe").build();
        assertEquals("ok", resolver.resolveTextClient(model).chat(request).getContent());
        assertSame(request, deepInfraCall.get());
    }

    @Test
    void deepInfraImagesAreDisabledAndNotTheDefaultOutOfTheBox() {
        AiModelRegistry registry = new AiModelRegistry(binder(Map.of()).bind("ai", AiModelProperties.class).get());

        assertEquals("gemini-image", registry.defaultFor(AiCapability.TEXT_TO_IMAGE).getKey());
        assertFalse(registry.find(IMAGE_KEY).orElseThrow().isEnabled());
    }

    @Test
    void imageProviderSwitchesToDeepInfraWhileTextStaysOnGemini() {
        Binder binder = binder(Map.of(
                "DEEPINFRA_IMAGE_ENABLED", "true",
                "AI_IMAGE_DEFAULT_MODEL", IMAGE_KEY,
                "DEEPINFRA_API_KEY", "env-supplied-key"));
        AiModelRegistry registry = new AiModelRegistry(binder.bind("ai", AiModelProperties.class).get());
        DeepInfraProperties properties = binder.bind("ai.deepinfra", DeepInfraProperties.class).get();

        assertEquals("gemini-flash", registry.defaultFor(AiCapability.TEXT_TO_TEXT).getKey());
        ModelDefinition image = registry.defaultFor(AiCapability.TEXT_TO_IMAGE);
        assertEquals(IMAGE_KEY, image.getKey());
        assertEquals("deepInfraImageClient", image.getProviderBean());
        assertEquals("black-forest-labs/FLUX-2-klein-4b", image.getProviderModelId());
        assertEquals("drawthings-sdxl", registry.fallbackFor(image).orElseThrow().getKey());

        // One key and base URL for text and images; only the image settings are separate.
        assertEquals("env-supplied-key", properties.getApiKey());
        assertEquals("https://api.deepinfra.com", properties.getBaseUrl());
        assertEquals("/v1/openai/images/generations", properties.getImage().getEndpoint());
        assertEquals("black-forest-labs/FLUX-2-klein-4b", properties.getImage().getModel());
        assertEquals(1024, properties.getImage().getWidth());
        assertEquals(1024, properties.getImage().getHeight());
        assertEquals(1, properties.getImage().getCount());

        AnalyticsPricingProperties pricing = binder.bind("vk.ai.pricing", AnalyticsPricingProperties.class).get();
        assertEquals(0.014, pricing.getPerImageUsd().get(IMAGE_KEY));
    }

    @Test
    void textAndImagesCanBothUseDeepInfraOrEachUseAnotherProvider() {
        AiModelRegistry both = new AiModelRegistry(binder(Map.of(
                "DEEPINFRA_ENABLED", "true", "AI_TEXT_DEFAULT_MODEL", KEY,
                "DEEPINFRA_IMAGE_ENABLED", "true", "AI_IMAGE_DEFAULT_MODEL", IMAGE_KEY))
                .bind("ai", AiModelProperties.class).get());
        assertEquals(KEY, both.defaultFor(AiCapability.TEXT_TO_TEXT).getKey());
        assertEquals(IMAGE_KEY, both.defaultFor(AiCapability.TEXT_TO_IMAGE).getKey());

        AiModelRegistry textOnly = new AiModelRegistry(binder(Map.of(
                "DEEPINFRA_ENABLED", "true", "AI_TEXT_DEFAULT_MODEL", KEY,
                "AI_IMAGE_DEFAULT_MODEL", "drawthings-sdxl"))
                .bind("ai", AiModelProperties.class).get());
        assertEquals(KEY, textOnly.defaultFor(AiCapability.TEXT_TO_TEXT).getKey());
        assertEquals("drawthings-sdxl", textOnly.defaultFor(AiCapability.TEXT_TO_IMAGE).getKey());

        AiModelRegistry imageDisabled = new AiModelRegistry(binder(Map.of("AI_IMAGE_DEFAULT_MODEL", IMAGE_KEY))
                .bind("ai", AiModelProperties.class).get());
        assertThrows(IllegalStateException.class, () -> imageDisabled.defaultFor(AiCapability.TEXT_TO_IMAGE));
    }

    @Test
    void resolverRoutesEachImageModelToItsOwnBean() throws Exception {
        ImageGenerationClient deepInfra = prompt -> new GeneratedImage("image/jpeg", new byte[]{1});
        ImageGenerationClient gemini = prompt -> new GeneratedImage("image/png", new byte[]{2});
        ImageGenerationClient drawThings = prompt -> new GeneratedImage("image/png", new byte[]{3});
        AiClientResolver resolver = new AiClientResolver(Map.of(), Map.of(
                "deepInfraImageClient", deepInfra, "geminiImageClient", gemini, "drawThingsImageClient", drawThings));

        assertEquals("image/jpeg", resolver.resolveImageClient(imageModel(IMAGE_KEY, "deepInfraImageClient")).generate("p").mimeType());
        assertEquals(2, resolver.resolveImageClient(imageModel("gemini-image", "geminiImageClient")).generate("p").data()[0]);
        assertEquals(3, resolver.resolveImageClient(imageModel("drawthings-sdxl", "drawThingsImageClient")).generate("p").data()[0]);
    }

    private static ModelDefinition imageModel(String key, String bean) {
        ModelDefinition model = new ModelDefinition();
        model.setKey(key);
        model.setCapability(AiCapability.TEXT_TO_IMAGE);
        model.setProviderBean(bean);
        return model;
    }

    /**
     * The registry entries as documented in exampleProps (application.properties itself is gitignored), plus the
     * given environment overrides; the real OS environment is left out.
     */
    private static Binder binder(Map<String, Object> env) {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new MapPropertySource("test-env", env));
        sources.addLast(new MapPropertySource("application", APPLICATION));
        return Binder.get(environment);
    }

    private static final Map<String, Object> APPLICATION = Map.ofEntries(
            Map.entry("ai.models[0].key", "gemini-flash"),
            Map.entry("ai.models[0].capability", "TEXT_TO_TEXT"),
            Map.entry("ai.models[0].tier", "PAID"),
            Map.entry("ai.models[0].provider-bean", "geminiAiClient"),
            Map.entry("ai.models[0].fallback-model-key", "ollama-qwen"),
            Map.entry("ai.models[1].key", "ollama-qwen"),
            Map.entry("ai.models[1].capability", "TEXT_TO_TEXT"),
            Map.entry("ai.models[1].tier", "OPEN_SOURCE"),
            Map.entry("ai.models[1].provider-bean", "ollamaAiClient"),
            Map.entry("ai.models[2].key", KEY),
            Map.entry("ai.models[2].capability", "TEXT_TO_TEXT"),
            Map.entry("ai.models[2].tier", "PAID"),
            Map.entry("ai.models[2].provider-bean", "deepInfraAiClient"),
            Map.entry("ai.models[2].provider-model-id", "${ai.deepinfra.defaultModel}"),
            Map.entry("ai.models[2].credit-cost", "1"),
            Map.entry("ai.models[2].input-usd-per-million", "0.20"),
            Map.entry("ai.models[2].cached-input-usd-per-million", "0.006"),
            Map.entry("ai.models[2].output-usd-per-million", "0.60"),
            Map.entry("ai.models[2].fallback-model-key", "ollama-qwen"),
            Map.entry("ai.models[2].enabled", "${DEEPINFRA_ENABLED:false}"),
            Map.entry("ai.models[3].key", "gemini-image"),
            Map.entry("ai.models[3].capability", "TEXT_TO_IMAGE"),
            Map.entry("ai.models[3].tier", "PAID"),
            Map.entry("ai.models[3].provider-bean", "geminiImageClient"),
            Map.entry("ai.models[3].fallback-model-key", "drawthings-sdxl"),
            Map.entry("ai.models[4].key", "drawthings-sdxl"),
            Map.entry("ai.models[4].capability", "TEXT_TO_IMAGE"),
            Map.entry("ai.models[4].tier", "OPEN_SOURCE"),
            Map.entry("ai.models[4].provider-bean", "drawThingsImageClient"),
            Map.entry("ai.models[5].key", IMAGE_KEY),
            Map.entry("ai.models[5].capability", "TEXT_TO_IMAGE"),
            Map.entry("ai.models[5].tier", "PAID"),
            Map.entry("ai.models[5].provider-bean", "deepInfraImageClient"),
            Map.entry("ai.models[5].provider-model-id", "${ai.deepinfra.image.model}"),
            Map.entry("ai.models[5].credit-cost", "2"),
            Map.entry("ai.models[5].fallback-model-key", "drawthings-sdxl"),
            Map.entry("ai.models[5].enabled", "${DEEPINFRA_IMAGE_ENABLED:false}"),
            Map.entry("vk.ai.pricing.per-image-usd.deepinfra-flux2-klein", "0.014"),
            Map.entry("ai.default-model.TEXT_TO_TEXT", "${AI_TEXT_DEFAULT_MODEL:gemini-flash}"),
            Map.entry("ai.default-model.TEXT_TO_IMAGE", "${AI_IMAGE_DEFAULT_MODEL:gemini-image}"),
            Map.entry("ai.deepinfra.apiKey", "${DEEPINFRA_API_KEY:}"),
            Map.entry("ai.deepinfra.baseUrl", "https://api.deepinfra.com"),
            Map.entry("ai.deepinfra.chatEndpoint", "/v1/openai/chat/completions"),
            Map.entry("ai.deepinfra.defaultModel", "${DEEPINFRA_MODEL:deepseek-ai/DeepSeek-V4-Flash}"),
            Map.entry("ai.deepinfra.reasoningEffort", "${DEEPINFRA_REASONING_EFFORT:none}"),
            Map.entry("ai.deepinfra.jsonSchemaEnabled", "${DEEPINFRA_JSON_SCHEMA_ENABLED:true}"),
            Map.entry("ai.deepinfra.image.endpoint", "/v1/openai/images/generations"),
            Map.entry("ai.deepinfra.image.model", "${DEEPINFRA_IMAGE_MODEL:black-forest-labs/FLUX-2-klein-4b}"),
            Map.entry("ai.deepinfra.image.width", "1024"),
            Map.entry("ai.deepinfra.image.height", "1024"),
            Map.entry("ai.deepinfra.image.count", "1"));
}
