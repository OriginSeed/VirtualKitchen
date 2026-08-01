package com.processVisualisation.virtualKitchen.ai.client.gemini;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.client.AIClient;
import com.processVisualisation.virtualKitchen.ai.config.GeminiProperties;
import com.processVisualisation.virtualKitchen.ai.dto.AIRequest;
import com.processVisualisation.virtualKitchen.ai.dto.AIResponse;
import com.processVisualisation.virtualKitchen.ai.exception.AIAuthenticationException;
import com.processVisualisation.virtualKitchen.ai.exception.AICommunicationException;
import com.processVisualisation.virtualKitchen.ai.exception.AIInvalidResponseException;
import com.processVisualisation.virtualKitchen.ai.exception.AITimeoutException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@ConditionalOnProperty(prefix = "ai", name = "provider", havingValue = "gemini")
@Component
public class GeminiClient implements AIClient {

    private final RestClient restClient;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeminiClient(
            @Qualifier("geminiRestClient") RestClient restClient,
            GeminiProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public AIResponse chat(AIRequest request) {
        validateConfiguration();
        validateRequest(request);

        String model = StringUtils.hasText(request.getModel()) ? request.getModel() : properties.getDefaultModel();
        long startedAt = System.nanoTime();

        System.out.println("[AI] Request start. provider=gemini model=" + model);

        try {
            String body = objectMapper.writeValueAsString(buildRequestPayload(request));

            String rawResponse = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path(properties.getChatEndpoint())
                            .build(Map.of("model", model)))
                    .header("x-goog-api-key", properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            long latencyMs = elapsedMs(startedAt);

            if (!StringUtils.hasText(rawResponse)) {
                System.out.println("[AI] Failure. provider=gemini reason=empty_response latencyMs=" + latencyMs);
                throw new AIInvalidResponseException("Received empty response from Gemini");
            }

            AIResponse parsed = parseResponse(rawResponse, model);
            System.out.println(
                    "[AI] Success. provider=gemini model=" + parsed.getModel()
                            + " latencyMs=" + latencyMs
                            + " promptTokens=" + safeInt(parsed.getPromptTokens())
                            + " completionTokens=" + safeInt(parsed.getCompletionTokens())
                            + " totalTokens=" + safeInt(parsed.getTotalTokens())
            );
            return parsed;
        } catch (RestClientResponseException ex) {
            long latencyMs = elapsedMs(startedAt);
            int status = ex.getStatusCode().value();
            String responseBody = ex.getResponseBodyAsString();
            if (status == 401 || status == 403) {
                System.out.println("[AI] Failure. provider=gemini reason=authentication_failed latencyMs=" + latencyMs);
                throw new AIAuthenticationException("Gemini authentication failed", ex);
            }

            if (status == 429) {
                String errorMessage = "Gemini quota exceeded (429 Too Many Requests)";
                try {
                    JsonNode errorNode = objectMapper.readTree(responseBody).path("error");
                    String msg = errorNode.path("message").asText(null);
                    String code = errorNode.path("code").asText(null);
                    if (msg != null) {
                        errorMessage += ": " + msg;
                    }
                    if (code != null) {
                        errorMessage += " code=" + code;
                    }
                } catch (Exception parseEx) {
                    // ignore JSON parse errors and keep generic message
                }
                System.out.println("[AI] Failure. provider=gemini reason=quota_exceeded status=429 latencyMs=" + latencyMs);
                throw new AICommunicationException(errorMessage, ex);
            }

            System.out.println("[AI] Failure. provider=gemini reason=http_error status=" + status
                    + " latencyMs=" + latencyMs + " body=" + responseBody);
            throw new AICommunicationException("Gemini API request failed with status: " + ex.getStatusCode(), ex);
        } catch (ResourceAccessException ex) {
            long latencyMs = elapsedMs(startedAt);
            System.out.println("[AI] Failure. provider=gemini reason=timeout_or_connectivity latencyMs=" + latencyMs);
            throw new AITimeoutException("Gemini request timed out or could not connect", ex);
        } catch (JsonProcessingException ex) {
            long latencyMs = elapsedMs(startedAt);
            System.out.println("[AI] Failure. provider=gemini reason=invalid_json latencyMs=" + latencyMs);
            throw new AIInvalidResponseException("Failed to process Gemini JSON payload", ex);
        } catch (IllegalArgumentException ex) {
            long latencyMs = elapsedMs(startedAt);
            System.out.println("[AI] Failure. provider=gemini reason=invalid_uri latencyMs=" + latencyMs);
            throw new AICommunicationException("Gemini endpoint configuration is invalid", ex);
        }
    }

    private Map<String, Object> buildRequestPayload(AIRequest request) {
        Map<String, Object> payload = new HashMap<>();
        List<Map<String, Object>> contents = new ArrayList<>();
        contents.add(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", request.getUserPrompt()))
        ));
        payload.put("contents", contents);

        if (StringUtils.hasText(request.getSystemPrompt())) {
            payload.put("systemInstruction", Map.of(
                    "parts", List.of(Map.of("text", request.getSystemPrompt()))
            ));
        }

        Map<String, Object> generationConfig = new HashMap<>();
        if (request.getTemperature() != null) {
            generationConfig.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            generationConfig.put("maxOutputTokens", request.getMaxTokens());
        }
        if (StringUtils.hasText(request.getResponseFormat())) {
            generationConfig.put("responseMimeType", mapResponseMimeType(request.getResponseFormat()));
        }

        if (!generationConfig.isEmpty()) {
            payload.put("generationConfig", generationConfig);
        }

        return payload;
    }

    private AIResponse parseResponse(String rawResponse, String requestedModel) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(rawResponse);

        JsonNode firstCandidate = root.path("candidates").path(0);
        String content = extractText(firstCandidate.path("content"));
        if (!StringUtils.hasText(content)) {
            throw new AIInvalidResponseException("Gemini response does not contain message content");
        }

        JsonNode usage = root.path("usageMetadata");

        return AIResponse.builder()
                .content(content)
                .model(root.path("modelVersion").asText(requestedModel))
                .promptTokens(readInt(usage, "promptTokenCount"))
                .completionTokens(readInt(usage, "candidatesTokenCount"))
                .totalTokens(readInt(usage, "totalTokenCount"))
                .finishReason(firstCandidate.path("finishReason").asText(null))
                .rawResponse(rawResponse)
                .build();
    }

    private String extractText(JsonNode contentNode) {
        JsonNode parts = contentNode.path("parts");
        if (!parts.isArray()) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        for (JsonNode part : parts) {
            String text = part.path("text").asText(null);
            if (!StringUtils.hasText(text)) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(text);
        }

        return builder.isEmpty() ? null : builder.toString();
    }

    private String mapResponseMimeType(String responseFormat) {
        String normalized = responseFormat.trim();
        if ("json".equalsIgnoreCase(normalized)
                || "json_object".equalsIgnoreCase(normalized)
                || MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(normalized)) {
            return MediaType.APPLICATION_JSON_VALUE;
        }
        if ("text".equalsIgnoreCase(normalized)
                || MediaType.TEXT_PLAIN_VALUE.equalsIgnoreCase(normalized)) {
            return MediaType.TEXT_PLAIN_VALUE;
        }
        return normalized;
    }

    private Integer readInt(JsonNode parent, String key) {
        JsonNode node = parent.path(key);
        return node.isMissingNode() || node.isNull() ? null : node.asInt();
    }

    private String safeInt(Integer value) {
        return value == null ? "NA" : value.toString();
    }

    private long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private void validateConfiguration() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new AICommunicationException("Gemini API key is not configured");
        }
        if (!StringUtils.hasText(properties.getBaseUrl())) {
            throw new AICommunicationException("Gemini base URL is not configured");
        }
        if (!StringUtils.hasText(properties.getChatEndpoint())) {
            throw new AICommunicationException("Gemini chat endpoint is not configured");
        }
        if (!StringUtils.hasText(properties.getDefaultModel())) {
            throw new AICommunicationException("Gemini default model is not configured");
        }
    }

    private void validateRequest(AIRequest request) {
        if (request == null) {
            throw new AICommunicationException("AI request cannot be null");
        }
        if (!StringUtils.hasText(request.getUserPrompt())) {
            throw new AICommunicationException("AI user prompt cannot be empty");
        }
    }
}
