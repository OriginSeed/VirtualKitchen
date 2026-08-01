package com.processVisualisation.virtualKitchen.ai.client.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.client.AIClient;
import com.processVisualisation.virtualKitchen.ai.config.OpenAIProperties;
import com.processVisualisation.virtualKitchen.ai.dto.AIRequest;
import com.processVisualisation.virtualKitchen.ai.dto.AIResponse;
import com.processVisualisation.virtualKitchen.ai.exception.AIAuthenticationException;
import com.processVisualisation.virtualKitchen.ai.exception.AICommunicationException;
import com.processVisualisation.virtualKitchen.ai.exception.AIInvalidResponseException;
import com.processVisualisation.virtualKitchen.ai.exception.AITimeoutException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
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

@ConditionalOnProperty(prefix = "ai", name = "provider", havingValue = "openai", matchIfMissing = true)
@Component
public class OpenAIClient implements AIClient {

    private final RestClient restClient;
    private final OpenAIProperties properties;
        private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAIClient(
            @Qualifier("openAiRestClient") RestClient restClient,
            OpenAIProperties properties
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

        System.out.println("[AI] Request start. provider=openai model=" + model);

        try {
            String body = objectMapper.writeValueAsString(buildRequestPayload(request, model));

            String rawResponse = restClient.post()
                    .uri(properties.getChatEndpoint())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            long latencyMs = elapsedMs(startedAt);

            if (!StringUtils.hasText(rawResponse)) {
                System.out.println("[AI] Failure. provider=openai reason=empty_response latencyMs=" + latencyMs);
                throw new AIInvalidResponseException("Received empty response from OpenAI");
            }

            AIResponse parsed = parseResponse(rawResponse);
            System.out.println(
                    "[AI] Success. provider=openai model=" + parsed.getModel()
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
                System.out.println("[AI] Failure. provider=openai reason=authentication_failed latencyMs=" + latencyMs);
                throw new AIAuthenticationException("OpenAI authentication failed", ex);
            }

            if (status == 429) {
                String errorMessage = "OpenAI quota exceeded (429 Too Many Requests)";
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
                System.out.println("[AI] Failure. provider=openai reason=quota_exceeded status=429 latencyMs=" + latencyMs);
                throw new AICommunicationException(errorMessage, ex);
            }

            System.out.println("[AI] Failure. provider=openai reason=http_error status=" + status
                    + " latencyMs=" + latencyMs + " body=" + (responseBody != null ? responseBody : "NA"));
            throw new AICommunicationException("OpenAI API request failed with status: " + ex.getStatusCode(), ex);
        } catch (ResourceAccessException ex) {
            long latencyMs = elapsedMs(startedAt);
            System.out.println("[AI] Failure. provider=openai reason=timeout_or_connectivity latencyMs=" + latencyMs);
            throw new AITimeoutException("OpenAI request timed out or could not connect", ex);
        } catch (JsonProcessingException ex) {
            long latencyMs = elapsedMs(startedAt);
            System.out.println("[AI] Failure. provider=openai reason=invalid_json latencyMs=" + latencyMs);
            throw new AIInvalidResponseException("Failed to process OpenAI JSON payload", ex);
        }
    }

    private Map<String, Object> buildRequestPayload(AIRequest request, String model) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);

        List<Map<String, String>> messages = new ArrayList<>();
        if (StringUtils.hasText(request.getSystemPrompt())) {
            messages.add(Map.of("role", "system", "content", request.getSystemPrompt()));
        }
        messages.add(Map.of("role", "user", "content", request.getUserPrompt()));
        payload.put("messages", messages);

        if (request.getTemperature() != null) {
            payload.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            payload.put("max_tokens", request.getMaxTokens());
        }
        if (StringUtils.hasText(request.getResponseFormat())) {
            payload.put("response_format", Map.of("type", request.getResponseFormat()));
        }
        return payload;
    }

    private AIResponse parseResponse(String rawResponse) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(rawResponse);

        JsonNode firstChoice = root.path("choices").path(0);
        String content = firstChoice.path("message").path("content").asText(null);
        if (!StringUtils.hasText(content)) {
            throw new AIInvalidResponseException("OpenAI response does not contain message content");
        }

        JsonNode usage = root.path("usage");

        return AIResponse.builder()
                .content(content)
                .model(root.path("model").asText(properties.getDefaultModel()))
                .promptTokens(readInt(usage, "prompt_tokens"))
                .completionTokens(readInt(usage, "completion_tokens"))
                .totalTokens(readInt(usage, "total_tokens"))
                .finishReason(firstChoice.path("finish_reason").asText(null))
                .rawResponse(rawResponse)
                .build();
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
            throw new AICommunicationException("OpenAI API key is not configured");
        }
        if (!StringUtils.hasText(properties.getBaseUrl())) {
            throw new AICommunicationException("OpenAI base URL is not configured");
        }
        if (!StringUtils.hasText(properties.getChatEndpoint())) {
            throw new AICommunicationException("OpenAI chat endpoint is not configured");
        }
        if (!StringUtils.hasText(properties.getDefaultModel())) {
            throw new AICommunicationException("OpenAI default model is not configured");
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
