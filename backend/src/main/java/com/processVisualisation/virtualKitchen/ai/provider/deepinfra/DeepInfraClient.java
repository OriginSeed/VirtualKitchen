package com.processVisualisation.virtualKitchen.ai.provider.deepinfra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.provider.AiClient;
import com.processVisualisation.virtualKitchen.ai.provider.config.DeepInfraProperties;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiRequest;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiResponse;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiAuthenticationException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiCommunicationException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiInvalidResponseException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiRateLimitedException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiTimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AiClient} implementation that talks to DeepInfra's OpenAI-compatible
 * chat completions API (e.g. DeepSeek V4 Flash). Registered under the bean
 * name {@code deepInfraAiClient} so the model registry can route to it via
 * {@code ai.models[n].provider-bean}, alongside every other provider bean.
 * Non-streaming only.
 */
@Component("deepInfraAiClient")
public class DeepInfraClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(DeepInfraClient.class);
    private static final int MAX_LOGGED_BODY_CHARS = 500;

    private final RestClient restClient;
    private final DeepInfraProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Creates a client bound to the DeepInfra REST client and configuration.
     *
     * @param restClient the pre-configured REST client used to call the DeepInfra API
     * @param properties the configured DeepInfra API key, endpoints, and default model
     */
    public DeepInfraClient(
            @Qualifier("deepInfraRestClient") RestClient restClient,
            DeepInfraProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * Sends a chat request to DeepInfra and returns the parsed response.
     *
     * @param request the prompt, model, and generation parameters to send
     * @return the parsed DeepInfra response, including content and usage metadata
     * @throws AiCommunicationException if DeepInfra is not configured, the request is invalid, or the API call fails
     * @throws AiAuthenticationException if DeepInfra rejects the API key (401/403)
     * @throws AiRateLimitedException if DeepInfra throttles the request (429) or the model is overloaded (503)
     * @throws AiTimeoutException if the request times out or the connection fails
     * @throws AiInvalidResponseException if DeepInfra returns an empty response, no message content, or unparsable JSON
     */
    @Override
    public AiResponse chat(AiRequest request) {
        validateConfiguration();
        validateRequest(request);

        String model = StringUtils.hasText(request.getModel()) ? request.getModel() : properties.getDefaultModel();

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

            if (!StringUtils.hasText(rawResponse)) {
                throw new AiInvalidResponseException("Received empty response from DeepInfra");
            }

            return parseResponse(rawResponse, model);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new AiAuthenticationException("DeepInfra authentication failed", ex);
            }
            // DeepInfra's 429 is a concurrency limit or a busy model, not a daily quota, so it is worth retrying.
            if (status == 429 || status == 503) {
                String reason = status == 429 ? "rate limited (429)" : "model overloaded (503)";
                throw new AiRateLimitedException("DeepInfra " + reason, retryAfter(ex), ex);
            }

            log.debug("event=ai_provider_http_error provider=deepinfra status={} body={}",
                    status, truncate(ex.getResponseBodyAsString()));
            throw new AiCommunicationException("DeepInfra API request failed with status: " + ex.getStatusCode(), ex);
        } catch (ResourceAccessException ex) {
            throw new AiTimeoutException("DeepInfra request timed out or could not connect", ex);
        } catch (JsonProcessingException ex) {
            throw new AiInvalidResponseException("Failed to process DeepInfra JSON payload", ex);
        }
    }

    Map<String, Object> buildRequestPayload(AiRequest request, String model) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("stream", false);

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
        if (StringUtils.hasText(properties.getReasoningEffort())) {
            payload.put("reasoning_effort", properties.getReasoningEffort());
        }
        if (request.getResponseSchema() != null && properties.isJsonSchemaEnabled()) {
            // Non-strict, as for OpenAI: strict mode would force every property to be present.
            payload.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                            "name", StringUtils.hasText(request.getResponseSchemaName()) ? request.getResponseSchemaName() : "response",
                            "schema", request.getResponseSchema(),
                            "strict", false
                    )
            ));
        } else if (request.getResponseSchema() != null) {
            payload.put("response_format", Map.of("type", "json_object"));
        } else if (StringUtils.hasText(request.getResponseFormat())) {
            payload.put("response_format", Map.of("type", request.getResponseFormat()));
        }
        return payload;
    }

    private AiResponse parseResponse(String rawResponse, String requestedModel) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(rawResponse);

        JsonNode firstChoice = root.path("choices").path(0);
        String finishReason = firstChoice.path("finish_reason").asText(null);
        String content = firstChoice.path("message").path("content").asText(null);
        if (!StringUtils.hasText(content)) {
            // A reasoning model that spends the whole max_tokens budget thinking returns no content (finish_reason=length).
            throw new AiInvalidResponseException(
                    "DeepInfra response does not contain message content (finish_reason=" + finishReason + ")");
        }

        JsonNode usage = root.path("usage");

        return AiResponse.builder()
                .content(content)
                .model(root.path("model").asText(requestedModel))
                .promptTokens(readInt(usage, "prompt_tokens"))
                .completionTokens(readInt(usage, "completion_tokens"))
                .totalTokens(readInt(usage, "total_tokens"))
                // OpenAI-style: cached tokens are a subset of prompt_tokens; null when DeepInfra omits the details.
                // Reasoning tokens stay inside completion_tokens, so thoughtsTokens is not set (no double billing).
                .cachedTokens(readInt(usage.path("prompt_tokens_details"), "cached_tokens"))
                .finishReason(finishReason)
                .rawResponse(rawResponse)
                .build();
    }

    private Integer readInt(JsonNode parent, String key) {
        JsonNode node = parent.path(key);
        return node.isMissingNode() || node.isNull() ? null : node.asInt();
    }

    /** The {@code Retry-After} header in whole seconds, or null when absent or not a number. */
    private static Duration retryAfter(RestClientResponseException ex) {
        HttpHeaders headers = ex.getResponseHeaders();
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void validateConfiguration() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new AiCommunicationException("DeepInfra API key is not configured");
        }
        if (!StringUtils.hasText(properties.getBaseUrl())) {
            throw new AiCommunicationException("DeepInfra base URL is not configured");
        }
        if (!StringUtils.hasText(properties.getChatEndpoint())) {
            throw new AiCommunicationException("DeepInfra chat endpoint is not configured");
        }
        if (!StringUtils.hasText(properties.getDefaultModel())) {
            throw new AiCommunicationException("DeepInfra default model is not configured");
        }
    }

    private void validateRequest(AiRequest request) {
        if (request == null) {
            throw new AiCommunicationException("AI request cannot be null");
        }
        if (!StringUtils.hasText(request.getUserPrompt())) {
            throw new AiCommunicationException("AI user prompt cannot be empty");
        }
    }

    /** Provider error bodies can echo request content, so they are only logged at DEBUG and truncated. */
    private static String truncate(String body) {
        if (body == null || body.length() <= MAX_LOGGED_BODY_CHARS) {
            return body;
        }
        return body.substring(0, MAX_LOGGED_BODY_CHARS) + "...[truncated]";
    }
}
