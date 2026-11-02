package com.processVisualisation.virtualKitchen.ai.chat;

import com.processVisualisation.virtualKitchen.ai.platform.config.AiGenerationParamsProperties;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.AiAnalyticsRecorder;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AnalyticsStatus;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.OperationContext;
import com.processVisualisation.virtualKitchen.ai.platform.dispatch.LoggingAiClient;
import com.processVisualisation.virtualKitchen.ai.platform.dispatch.MeteredAiClient;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelRegistry;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import com.processVisualisation.virtualKitchen.ai.platform.response.AiResponseService;
import com.processVisualisation.virtualKitchen.ai.platform.usage.AiUsage;
import com.processVisualisation.virtualKitchen.ai.platform.usage.AiUsageMeter;
import com.processVisualisation.virtualKitchen.ai.provider.AiClient;
import com.processVisualisation.virtualKitchen.ai.provider.config.GeminiProperties;
import com.processVisualisation.virtualKitchen.ai.provider.config.OpenAIProperties;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiRequest;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiResponse;
import com.processVisualisation.virtualKitchen.common.logging.MdcKeys;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Builds a chat request for
 * the configured AI provider (OpenAI or Gemini, selected via the
 * {@code ai.provider} property) and delegates the actual call to
 * {@link AiClient}.
 * <p>
 * Chat does not go through the AI request queue (no credits are reserved for it), but every call is still recorded
 * in AI analytics, with its output kept in {@code ai_responses}.
 */
@Service
public class AiChatService {

    private final AiClient aiClient;
    private final OpenAIProperties openAIProperties;
    private final GeminiProperties geminiProperties;
    private final String provider;
    private final AiAnalyticsRecorder analytics;
    private final AiModelRegistry modelRegistry;
    private final AiResponseService aiResponseService;

    public AiChatService(AiClient aiClient,
                         OpenAIProperties openAIProperties,
                         ObjectProvider<GeminiProperties> geminiPropertiesProvider,
                         @Value("${ai.provider:gemini}") String provider,
                         AiAnalyticsRecorder analytics,
                         AiModelRegistry modelRegistry,
                         AiResponseService aiResponseService) {
        // The injected client is the @Primary provider bean, not one resolved from the model registry.
        String providerLabel = LoggingAiClient.providerOf(StringUtils.uncapitalize(aiClient.getClass().getSimpleName()));
        this.aiClient = new MeteredAiClient(new LoggingAiClient(aiClient, providerLabel), providerLabel);
        this.openAIProperties = openAIProperties;
        this.geminiProperties = geminiPropertiesProvider.getIfAvailable();
        this.provider = provider == null ? "gemini" : provider;
        this.analytics = analytics;
        this.modelRegistry = modelRegistry;
        this.aiResponseService = aiResponseService;
    }

    /** Sampling parameters; admin runtime overrides apply to the next request. Defaults when not injected (tests). */
    private AiGenerationParamsProperties generationParams = new AiGenerationParamsProperties();

    @Autowired
    void setGenerationParams(AiGenerationParamsProperties generationParams) {
        this.generationParams = generationParams;
    }

    /**
     * Builds a chat request with a fixed system prompt and the configured
     * provider's default model, then sends it via {@link AiClient}.
     *
     * @param prompt the free-form user prompt
     * @return the AI provider's response; {@code analyticsRequestId} is set when the call and its output were
     *         recorded (the caller then needs no {@code ai_responses} record of its own)
     */
    public AiResponse chat(String prompt) {
        String defaultModel = openAIProperties.getDefaultModel();
        if ("gemini".equalsIgnoreCase(provider) && geminiProperties != null && geminiProperties.getDefaultModel() != null) {
            defaultModel = geminiProperties.getDefaultModel();
        }

        AiRequest request = AiRequest.builder()
                .systemPrompt("You are a helpful assistant for Virtual Kitchen.")
                .userPrompt(prompt)
                .model(defaultModel)
                .temperature(generationParams.getChat().getTemperature())
                .maxTokens(generationParams.getChat().getMaxTokens())
                .operation("CHAT")
                .build();

        String analyticsId = analytics.begin(new AiAnalyticsRecorder.Begin(null, currentUserId(),
                AiCapability.TEXT_TO_TEXT, OperationContext.CHAT, "chat", null, null, null, null, null));
        AiAnalyticsRecorder.RequestTracker tracker = analytics.tracker(analyticsId, OperationContext.CHAT);
        // Priced with the registry entry for the model id actually sent, when there is one.
        Optional<ModelDefinition> model = registryModelFor(request.getModel());
        model.ifPresent(definition -> {
            tracker.model(definition);
            analytics.modelResolved(analyticsId, definition, false, "NONE", 0, null, null, null);
        });

        AiUsage usage = new AiUsage();
        usage.setCallListener(tracker);
        tracker.markWorkStarted();
        AiResponse response;
        try {
            response = AiUsageMeter.measure(usage, () -> aiClient.chat(request));
        } catch (RuntimeException e) {
            analytics.fail(analyticsId, e, 1, tracker.workStartedAt());
            throw e;
        } catch (Exception e) {
            analytics.fail(analyticsId, e, 1, tracker.workStartedAt());
            throw new IllegalStateException(e);
        }
        analytics.complete(analyticsId, AnalyticsStatus.SUCCEEDED, null, 1, tracker.workStartedAt());

        String outputId = tracker.lastTextOutputId();
        if (response != null && outputId != null) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("rawResponse", response.getRawResponse());
            boolean success = response.getContent() != null && !response.getContent().isEmpty();
            if (aiResponseService.annotate(outputId, "chat", success, extra).isPresent()) {
                response.setAnalyticsRequestId(analyticsId);
            }
        }
        return response;
    }

    private Optional<ModelDefinition> registryModelFor(String providerModelId) {
        if (providerModelId == null) {
            return Optional.empty();
        }
        return modelRegistry.byCapability(AiCapability.TEXT_TO_TEXT).stream()
                .filter(model -> providerModelId.equals(model.getProviderModelId()))
                .findFirst();
    }

    private static Long currentUserId() {
        String userId = MDC.get(MdcKeys.USER_ID);
        try {
            return userId == null ? null : Long.valueOf(userId);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
