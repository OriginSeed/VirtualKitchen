package com.processVisualisation.virtualKitchen.ai.platform.dispatch;

import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import com.processVisualisation.virtualKitchen.ai.provider.AiClient;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient;
import com.processVisualisation.virtualKitchen.ai.provider.tts.TtsProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Maps a {@link ModelDefinition} to the concrete {@code AiClient}/{@code
 * ImageGenerationClient}/{@code TtsProvider} bean that should handle it. Spring
 * auto-populates the constructor-injected maps below with every bean of that
 * type, keyed by bean name — each provider client is registered under a stable
 * name (see {@code GeminiClient}, {@code OpenAIClient}, {@code OllamaClient},
 * {@code GeminiImageClient}, {@code DrawThingsImageClient}, {@code
 * GeminiTtsProvider}, {@code LocalTtsProvider}) specifically so they can all
 * coexist here instead of only one being selectable at boot time.
 */
@Component
public class AiClientResolver {

    private final Map<String, AiClient> textClients;
    private final Map<String, ImageGenerationClient> imageClients;
    private final Map<String, TtsProvider> ttsProviders;

    @Autowired
    public AiClientResolver(Map<String, AiClient> textClients,
                            Map<String, ImageGenerationClient> imageClients,
                            Map<String, TtsProvider> ttsProviders) {
        this.textClients = textClients;
        this.imageClients = imageClients;
        this.ttsProviders = ttsProviders;
    }

    /** For callers (mostly tests) that never resolve a speech model. */
    public AiClientResolver(Map<String, AiClient> textClients, Map<String, ImageGenerationClient> imageClients) {
        this(textClients, imageClients, Map.of());
    }

    /** Names of the provider beans that can serve {@code capability}, for the admin "add model" form. */
    public List<String> providerBeans(AiCapability capability) {
        Map<String, ?> beans = switch (capability) {
            case TEXT_TO_TEXT -> textClients;
            case TEXT_TO_IMAGE -> imageClients;
            case TEXT_TO_SPEECH -> ttsProviders;
        };
        return beans.keySet().stream().sorted().toList();
    }

    public AiClient resolveTextClient(ModelDefinition model) {
        AiClient client = textClients.get(model.getProviderBean());
        if (client == null) {
            throw new IllegalStateException(
                    "No AiClient bean named '" + model.getProviderBean() + "' for model '" + model.getKey() + "'");
        }
        String provider = LoggingAiClient.providerOf(model.getProviderBean());
        return new MeteredAiClient(new LoggingAiClient(client, provider), provider);
    }

    public ImageGenerationClient resolveImageClient(ModelDefinition model) {
        ImageGenerationClient client = imageClients.get(model.getProviderBean());
        if (client == null) {
            throw new IllegalStateException(
                    "No ImageGenerationClient bean named '" + model.getProviderBean() + "' for model '" + model.getKey() + "'");
        }
        return new LoggingImageGenerationClient(
                client, LoggingAiClient.providerOf(model.getProviderBean()), model.getProviderModelId());
    }

    public TtsProvider resolveTtsProvider(ModelDefinition model) {
        TtsProvider provider = ttsProviders.get(model.getProviderBean());
        if (provider == null) {
            throw new IllegalStateException(
                    "No TtsProvider bean named '" + model.getProviderBean() + "' for model '" + model.getKey() + "'");
        }
        return new LoggingTtsProvider(provider, model.getKey());
    }
}
