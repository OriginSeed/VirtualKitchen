package com.processVisualisation.virtualKitchen.ai.provider.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.provider.AiClient;
import com.processVisualisation.virtualKitchen.ai.provider.config.OpenAIProperties;
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
 * {@link AiClient} implementation that talks to OpenAI's chat completion
 * API. Used by the Virtual Kitchen application as a text-generation
 * provider. Registered under the bean name {@code openAiAiClient} so it can
 * be selected per-request by the AI model routing layer alongside every
 * other provider bean.
 */
@Component("openAiAiClient")
public class OpenAIClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAIClient.class);
    private static final int MAX_LOGGED_BODY_CHARS = 500;

    private final RestClient restClient;
    private final OpenAIProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Creates a client bound to the OpenAI REST client and configuration.
     *
     * @param restClient the pre-configured REST client used to call the OpenAI API
     * @param properties the configured OpenAI API key, endpoints, and default model
     */
    public OpenAIClient(
            @Qualifier("openAiRestClient") RestClient restClient,
            OpenAIProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * Sends a chat request to the OpenAI API and returns the parsed
     * response. Validates configuration and the request, builds the OpenAI
     * request payload, logs request/response outcomes, and maps HTTP and
     * connectivity failures to the appropriate AI client exception subtype.
     *
     * @param request the prompt, model, and generation parameters to send
     * @return the parsed OpenAI response, including content and usage metadata
     * @throws AiCommunicationException if OpenAI is not configured correctly, the request is invalid, or the API call fails with a non-authentication HTTP error
     * @throws AiAuthenticationException if OpenAI rejects the request due to invalid or missing credentials
     * @throws AiTimeoutException if the request times out or the connection fails
     * @throws AiInvalidResponseException if OpenAI returns an empty response, a response without message content, or unparsable JSON
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
                throw new AiInvalidResponseException("Received empty response from OpenAI");
            }

            AiResponse parsed = parseResponse(rawResponse);
            return parsed;
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String responseBody = ex.getResponseBodyAsString();
            if (status == 401 || status == 403) {
                throw new AiAuthenticationException("OpenAI authentication failed", ex);
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
                // insufficient_quota means the credits are spent: retrying cannot help. Any other 429 is a rate limit.
                if (errorMessage.contains("insufficient_quota")) {
                    throw new AiCommunicationException(errorMessage, ex);
                }
                throw new AiRateLimitedException(errorMessage, retryAfter(ex), ex);
            }

            log.debug("event=ai_provider_http_error provider=openai status={} body={}", status, truncate(responseBody));
            throw new AiCommunicationException("OpenAI API request failed with status: " + ex.getStatusCode()
                    + errorMessage(responseBody), ex);
        } catch (ResourceAccessException ex) {
            throw new AiTimeoutException("OpenAI request timed out or could not connect", ex);
        } catch (JsonProcessingException ex) {
            throw new AiInvalidResponseException("Failed to process OpenAI JSON payload", ex);
        }
    }

    private Map<String, Object> buildRequestPayload(AiRequest request, String model) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);

        List<Map<String, String>> messages = new ArrayList<>();
        if (StringUtils.hasText(request.getSystemPrompt())) {
            messages.add(Map.of("role", "system", "content", request.getSystemPrompt()));
        }
        messages.add(Map.of("role", "user", "content", request.getUserPrompt()));
        payload.put("messages", messages);

        if (request.getTemperature() != null && !isReasoningModel(model)) {
            payload.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            // Newer models (gpt-5 and later, o-series) reject max_tokens.
            payload.put(isReasoningModel(model) ? "max_completion_tokens" : "max_tokens", request.getMaxTokens());
        }
        if (request.getResponseSchema() != null) {
            // Non-strict: strict mode would force every property to be present, defeating omit-when-empty schemas.
            payload.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                            "name", StringUtils.hasText(request.getResponseSchemaName()) ? request.getResponseSchemaName() : "response",
                            "schema", request.getResponseSchema(),
                            "strict", false
                    )
            ));
        } else if (StringUtils.hasText(request.getResponseFormat())) {
            payload.put("response_format", Map.of("type", request.getResponseFormat()));
        }
        return payload;
    }

    /**
     * Whether the model takes the newer chat parameters: {@code max_completion_tokens} instead of {@code max_tokens}, and
     * only the default temperature. True for every model except the older gpt-3.5 / gpt-4 families (gpt-4, gpt-4o,
     * gpt-4.1), so new families (gpt-5, gpt-6-luna, the o-series, ...) work without a code change.
     */
    static boolean isReasoningModel(String model) {
        if (model == null) return false;
        return !model.toLowerCase(java.util.Locale.ROOT).matches("^(chatgpt-4o|gpt-3\\.5|gpt-4).*");
    }

    /** OpenAI's own error message (e.g. an unsupported parameter), so a failure says why in the logs; "" when none. */
    private String errorMessage(String responseBody) {
        try {
            String message = objectMapper.readTree(responseBody).path("error").path("message").asText(null);
            return StringUtils.hasText(message) ? ": " + message : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** The {@code Retry-After} header in whole seconds, or null when absent or not a number. */
    static Duration retryAfter(RestClientResponseException ex) {
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

    private AiResponse parseResponse(String rawResponse) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(rawResponse);

        JsonNode firstChoice = root.path("choices").path(0);
        String content = firstChoice.path("message").path("content").asText(null);
        if (!StringUtils.hasText(content)) {
            throw new AiInvalidResponseException("OpenAI response does not contain message content");
        }

        JsonNode usage = root.path("usage");

        return AiResponse.builder()
                .content(content)
                .model(root.path("model").asText(properties.getDefaultModel()))
                .promptTokens(readInt(usage, "prompt_tokens"))
                .completionTokens(readInt(usage, "completion_tokens"))
                .totalTokens(readInt(usage, "total_tokens"))
                // Included in prompt_tokens, as Gemini's cached count is. Reasoning tokens are not mapped to
                // thoughtsTokens: OpenAI already counts them inside completion_tokens.
                .cachedTokens(readInt(usage.path("prompt_tokens_details"), "cached_tokens"))
                .finishReason(firstChoice.path("finish_reason").asText(null))
                .rawResponse(rawResponse)
                .build();
    }

    private Integer readInt(JsonNode parent, String key) {
        JsonNode node = parent.path(key);
        return node.isMissingNode() || node.isNull() ? null : node.asInt();
    }

    private void validateConfiguration() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new AiCommunicationException("OpenAI API key is not configured");
        }
        if (!StringUtils.hasText(properties.getBaseUrl())) {
            throw new AiCommunicationException("OpenAI base URL is not configured");
        }
        if (!StringUtils.hasText(properties.getChatEndpoint())) {
            throw new AiCommunicationException("OpenAI chat endpoint is not configured");
        }
        if (!StringUtils.hasText(properties.getDefaultModel())) {
            throw new AiCommunicationException("OpenAI default model is not configured");
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
