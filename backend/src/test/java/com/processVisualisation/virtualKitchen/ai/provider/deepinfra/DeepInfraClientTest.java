package com.processVisualisation.virtualKitchen.ai.provider.deepinfra;

import com.processVisualisation.virtualKitchen.ai.provider.config.DeepInfraProperties;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiRequest;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiResponse;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiAuthenticationException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiClientException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiCommunicationException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiInvalidResponseException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiRateLimitedException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * DeepInfra's OpenAI-compatible chat completions: request mapping, response/usage parsing and error mapping,
 * all against a mocked HTTP server (never the real API).
 */
class DeepInfraClientTest {

    private static final String BASE_URL = "https://deepinfra.example";
    private static final String CHAT_URL = BASE_URL + "/v1/openai/chat/completions";
    private static final String MODEL = "deepseek-ai/DeepSeek-V4-Flash";
    private static final String API_KEY = "sentinel-deepinfra-key-31337";

    private static final String SUCCESS_BODY = """
            {
              "id": "chatcmpl-1",
              "object": "chat.completion",
              "model": "deepseek-ai/DeepSeek-V4-Flash",
              "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": "{\\"steps\\":[],\\"edges\\":[]}"},
                "finish_reason": "stop"
              }],
              "usage": {
                "prompt_tokens": 12000,
                "completion_tokens": 800,
                "total_tokens": 12800,
                "prompt_tokens_details": {"cached_tokens": 10240},
                "estimated_cost": 0.0029
              }
            }
            """;

    private DeepInfraProperties properties;
    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        properties = new DeepInfraProperties();
        properties.setApiKey(API_KEY);
        properties.setBaseUrl(BASE_URL);
        properties.setChatEndpoint("/v1/openai/chat/completions");
        properties.setDefaultModel(MODEL);
        properties.setReasoningEffort("none");

        builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    void successfulCompletionMapsPromptsModelAndParametersAndParsesTheResponse() {
        server.expect(once(), requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value("custom/model"))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("system rules"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("recipe text"))
                .andExpect(jsonPath("$.temperature").value(0.1))
                .andExpect(jsonPath("$.max_tokens").value(10000))
                .andExpect(jsonPath("$.reasoning_effort").value("none"))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        AiResponse response = client().chat(AiRequest.builder()
                .systemPrompt("system rules")
                .userPrompt("recipe text")
                .model("custom/model")
                .temperature(0.1)
                .maxTokens(10000)
                .build());

        server.verify();
        assertEquals("{\"steps\":[],\"edges\":[]}", response.getContent());
        assertEquals(MODEL, response.getModel());
        assertEquals("stop", response.getFinishReason());
        assertEquals(SUCCESS_BODY, response.getRawResponse());
    }

    @Test
    void usageIncludingCachedPromptTokensIsParsed() {
        server.expect(once(), requestTo(CHAT_URL)).andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        AiResponse response = client().chat(userOnly());

        assertEquals(12000, response.getPromptTokens());
        assertEquals(800, response.getCompletionTokens());
        assertEquals(12800, response.getTotalTokens());
        assertEquals(10240, response.getCachedTokens());
        assertNull(response.getThoughtsTokens(), "reasoning stays inside completion_tokens; never billed twice");
    }

    @Test
    void missingUsageLeavesTokenCountsNullRatherThanZero() {
        server.expect(once(), requestTo(CHAT_URL)).andRespond(withSuccess("""
                {"model": "m", "choices": [{"message": {"content": "ok"}, "finish_reason": "stop"}]}
                """, MediaType.APPLICATION_JSON));

        AiResponse response = client().chat(userOnly());

        assertEquals("ok", response.getContent());
        assertNull(response.getPromptTokens());
        assertNull(response.getCompletionTokens());
        assertNull(response.getTotalTokens());
        assertNull(response.getCachedTokens());
    }

    @Test
    void userOnlyRequestUsesTheDefaultModelAndOmitsUnsetParameters() {
        properties.setReasoningEffort(null);
        server.expect(once(), requestTo(CHAT_URL))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.temperature").doesNotExist())
                .andExpect(jsonPath("$.max_tokens").doesNotExist())
                .andExpect(jsonPath("$.reasoning_effort").doesNotExist())
                .andExpect(jsonPath("$.response_format").doesNotExist())
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        client().chat(userOnly());
        server.verify();
    }

    @Test
    void responseSchemaIsSentAsNonStrictJsonSchema() {
        server.expect(once(), requestTo(CHAT_URL))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.name").value("recipe_process"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(false))
                .andExpect(jsonPath("$.response_format.json_schema.schema.type").value("object"))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        client().chat(schemaRequest());
        server.verify();
    }

    @Test
    void schemaDegradesToJsonModeWhenJsonSchemaIsDisabled() {
        properties.setJsonSchemaEnabled(false);
        server.expect(once(), requestTo(CHAT_URL))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.response_format.json_schema").doesNotExist())
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        client().chat(schemaRequest());
        server.verify();
    }

    @Test
    void plainResponseFormatIsPassedThrough() {
        server.expect(once(), requestTo(CHAT_URL))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        client().chat(AiRequest.builder().userPrompt("hi").responseFormat("json_object").build());
        server.verify();
    }

    @Test
    void unauthorizedAndForbiddenAreAuthenticationFailures() {
        server.expect(once(), requestTo(CHAT_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .body("{\"detail\":{\"error\":\"Invalid token " + API_KEY + "\"}}").contentType(MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(CHAT_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        AiAuthenticationException first = assertThrows(AiAuthenticationException.class, () -> client().chat(userOnly()));
        assertThrows(AiAuthenticationException.class, () -> client().chat(userOnly()));
        assertKeyNotExposed(first);
    }

    @Test
    void rateLimitIsRetryableAndCarriesRetryAfter() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "7");
        server.expect(once(), requestTo(CHAT_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers)
                .body("{\"error\":{\"message\":\"Model busy, retry later\",\"code\":\"engine_overloaded\"}}")
                .contentType(MediaType.APPLICATION_JSON));

        AiRateLimitedException ex = assertThrows(AiRateLimitedException.class, () -> client().chat(userOnly()));

        assertEquals(Duration.ofSeconds(7), ex.getRetryAfter());
        assertTrue(ex.getMessage().contains("429"));
        assertKeyNotExposed(ex);
    }

    @Test
    void rateLimitWithoutRetryAfterAndOverloadedModelAreBothRetryable() {
        server.expect(once(), requestTo(CHAT_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(once(), requestTo(CHAT_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertNull(assertThrows(AiRateLimitedException.class, () -> client().chat(userOnly())).getRetryAfter());
        assertThrows(AiRateLimitedException.class, () -> client().chat(userOnly()));
    }

    @Test
    void otherHttpErrorsAreCommunicationFailuresThatDoNotEchoTheBody() {
        server.expect(once(), requestTo(CHAT_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("prompt echo: secret recipe"));
        server.expect(once(), requestTo(CHAT_URL)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .body("{\"detail\":[{\"loc\":[\"body\"],\"msg\":\"bad\",\"type\":\"value_error\"}]}"));

        AiCommunicationException serverError = assertThrows(AiCommunicationException.class, () -> client().chat(userOnly()));
        assertFalse(serverError instanceof AiRateLimitedException);
        assertTrue(serverError.getMessage().contains("500"));
        assertFalse(serverError.getMessage().contains("secret recipe"));
        AiCommunicationException validation = assertThrows(AiCommunicationException.class, () -> client().chat(userOnly()));
        assertTrue(validation.getMessage().contains("422"));
    }

    @Test
    void timeoutIsReportedAsAiTimeoutException() {
        server.expect(once(), requestTo(CHAT_URL)).andRespond(request -> {
            throw new SocketTimeoutException("Read timed out");
        });

        AiTimeoutException ex = assertThrows(AiTimeoutException.class, () -> client().chat(userOnly()));
        assertKeyNotExposed(ex);
    }

    @Test
    void emptyBodyIsAnInvalidResponse() {
        server.expect(once(), requestTo(CHAT_URL)).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThrows(AiInvalidResponseException.class, () -> client().chat(userOnly()));
    }

    @Test
    void missingContentIsAnInvalidResponseNamingTheFinishReason() {
        server.expect(once(), requestTo(CHAT_URL)).andRespond(withSuccess("""
                {"choices": [{"message": {"role": "assistant", "content": null, "reasoning_content": "thinking..."},
                  "finish_reason": "length"}]}
                """, MediaType.APPLICATION_JSON));

        AiInvalidResponseException ex = assertThrows(AiInvalidResponseException.class, () -> client().chat(userOnly()));
        assertTrue(ex.getMessage().contains("finish_reason=length"), ex.getMessage());
    }

    @Test
    void emptyChoicesAndMalformedJsonAreInvalidResponses() {
        server.expect(once(), requestTo(CHAT_URL)).andRespond(withSuccess("{\"choices\": []}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(CHAT_URL)).andRespond(withSuccess("{not json", MediaType.APPLICATION_JSON));

        assertThrows(AiInvalidResponseException.class, () -> client().chat(userOnly()));
        assertThrows(AiInvalidResponseException.class, () -> client().chat(userOnly()));
    }

    @Test
    void missingApiKeyFailsWithoutCallingDeepInfra() {
        properties.setApiKey("  ");

        AiCommunicationException ex = assertThrows(AiCommunicationException.class, () -> client().chat(userOnly()));
        assertTrue(ex.getMessage().contains("API key is not configured"));
        server.verify();
    }

    @Test
    void emptyUserPromptIsRejectedBeforeAnyCall() {
        assertThrows(AiCommunicationException.class, () -> client().chat(AiRequest.builder().systemPrompt("s").build()));
        assertThrows(AiCommunicationException.class, () -> client().chat(null));
        server.verify();
    }

    @Test
    void apiKeyIsNeverWrittenIntoTheRequestBody() {
        server.expect(once(), requestTo(CHAT_URL))
                .andExpect(content().string(not(containsString(API_KEY))))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        client().chat(userOnly());
        server.verify();
    }

    private DeepInfraClient client() {
        return new DeepInfraClient(builder.build(), properties);
    }

    private static AiRequest userOnly() {
        return AiRequest.builder().userPrompt("recipe text").build();
    }

    private static AiRequest schemaRequest() {
        return AiRequest.builder()
                .userPrompt("recipe text")
                .responseFormat("json_object")
                .responseSchema(Map.of("type", "object", "properties", Map.of("steps", Map.of("type", "array"))))
                .responseSchemaName("recipe_process")
                .build();
    }

    private static void assertKeyNotExposed(AiClientException ex) {
        assertInstanceOf(AiClientException.class, ex);
        assertFalse(String.valueOf(ex.getMessage()).contains(API_KEY), "exception message must not expose the API key");
    }
}
