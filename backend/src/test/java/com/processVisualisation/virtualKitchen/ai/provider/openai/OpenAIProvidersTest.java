package com.processVisualisation.virtualKitchen.ai.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient;
import com.processVisualisation.virtualKitchen.ai.provider.config.OpenAIProperties;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiRequest;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiResponse;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiAuthenticationException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiCommunicationException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiRateLimitedException;
import com.processVisualisation.virtualKitchen.ai.provider.tts.AudioFormat;
import com.processVisualisation.virtualKitchen.ai.provider.tts.GeneratedAudio;
import com.processVisualisation.virtualKitchen.ai.provider.tts.TtsRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * OpenAI text, image and speech clients: request mapping and error handling against a mocked HTTP server
 * (never the real API).
 */
class OpenAIProvidersTest {

    private static final String BASE_URL = "https://openai.example";
    private static final String API_KEY = "sentinel-openai-key-31337";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final byte[] MP3 = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFB};

    private OpenAIProperties properties;
    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        properties = new OpenAIProperties();
        properties.setApiKey(API_KEY);
        properties.setBaseUrl(BASE_URL);
        properties.setChatEndpoint("/v1/chat/completions");
        properties.setDefaultModel("gpt-4o-mini");
        builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
    }

    // ---- text ----

    private AiResponse chat(String model, Double temperature, Integer maxTokens) {
        return new OpenAIClient(builder.build(), properties).chat(AiRequest.builder()
                .userPrompt("hi").model(model).temperature(temperature).maxTokens(maxTokens).build());
    }

    @Test
    void textSendsMaxTokensAndTemperatureForClassicModels() {
        server.expect(once(), requestTo(BASE_URL + "/v1/chat/completions"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value("gpt-4.1"))
                .andExpect(jsonPath("$.max_tokens").value(100))
                .andExpect(jsonPath("$.temperature").value(0.3))
                .andRespond(withSuccess("{\"model\":\"gpt-4.1\",\"choices\":[{\"message\":{\"content\":\"ok\"},"
                        + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,"
                        + "\"total_tokens\":4}}", MediaType.APPLICATION_JSON));

        AiResponse response = chat("gpt-4.1", 0.3, 100);

        server.verify();
        assertEquals("ok", response.getContent());
        assertEquals(3, response.getPromptTokens());
    }

    @Test
    void textUsesMaxCompletionTokensAndNoTemperatureForReasoningModels() {
        assertTrue(OpenAIClient.isReasoningModel("gpt-5-mini"));
        assertTrue(OpenAIClient.isReasoningModel("o3-mini"));
        assertFalse(OpenAIClient.isReasoningModel("gpt-4.1"));
        assertFalse(OpenAIClient.isReasoningModel("gpt-4o-mini"));
        assertTrue(OpenAIClient.isReasoningModel("gpt-6-luna"), "a new family gets the new parameters");
        assertTrue(OpenAIClient.isReasoningModel("gpt-5.1"));
        assertFalse(OpenAIClient.isReasoningModel("gpt-4"));
        assertFalse(OpenAIClient.isReasoningModel("gpt-3.5-turbo"));
        server.expect(once(), requestTo(BASE_URL + "/v1/chat/completions"))
                .andExpect(jsonPath("$.max_completion_tokens").value(100))
                .andExpect(jsonPath("$.max_tokens").doesNotExist())
                .andExpect(jsonPath("$.temperature").doesNotExist())
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                        MediaType.APPLICATION_JSON));

        chat("gpt-5-mini", 0.3, 100);

        server.verify();
    }

    @Test
    void textRateLimitIsRetryableButSpentCreditsAreNot() {
        server.expect(once(), requestTo(BASE_URL + "/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"slow down\",\"code\":\"rate_limit_exceeded\"}}"));
        AiRateLimitedException limited = assertThrows(AiRateLimitedException.class, () -> chat(null, null, null));
        assertEquals(Duration.ofSeconds(7), limited.getRetryAfter());

        server.reset();
        server.expect(once(), requestTo(BASE_URL + "/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"no credits\",\"code\":\"insufficient_quota\"}}"));
        AiCommunicationException spent = assertThrows(AiCommunicationException.class, () -> chat(null, null, null));
        assertFalse(spent instanceof AiRateLimitedException);
    }

    // ---- image ----

    private ImageGenerationClient imageClient() {
        return new OpenAIImageClient(builder.build(), properties, new ObjectMapper());
    }

    private static String imageJson(byte[] bytes) {
        return "{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(bytes) + "\"}],"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":1056,\"total_tokens\":1066}}";
    }

    @Test
    void gptImageRequestsPngAndReadsTokenUsage() throws Exception {
        server.expect(once(), requestTo(BASE_URL + "/v1/images/generations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value("gpt-image-1"))
                .andExpect(jsonPath("$.prompt").value("a pan"))
                .andExpect(jsonPath("$.output_format").value("png"))
                .andExpect(jsonPath("$.response_format").doesNotExist())
                .andExpect(jsonPath("$.size").value("1024x1024"))
                .andRespond(withSuccess(imageJson(PNG), MediaType.APPLICATION_JSON));

        ImageGenerationClient.GeneratedImage image = imageClient().generate("a pan");

        server.verify();
        assertEquals("image/png", image.mimeType());
        assertArrayEquals(PNG, image.data());
        assertEquals(1056L, image.usage().outputTokens());
        assertEquals(1, image.usage().imageCount());
    }

    @Test
    void dalleRequestsBase64AndUsesTheRegistryModelAndSize() throws Exception {
        server.expect(once(), requestTo(BASE_URL + "/v1/images/generations"))
                .andExpect(jsonPath("$.model").value("dall-e-3"))
                .andExpect(jsonPath("$.response_format").value("b64_json"))
                .andExpect(jsonPath("$.output_format").doesNotExist())
                .andExpect(jsonPath("$.size").value("1792x1024"))
                .andRespond(withSuccess("{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(PNG)
                        + "\"}]}", MediaType.APPLICATION_JSON));

        ImageGenerationClient.GeneratedImage image = imageClient().generate("a pan", List.of(),
                new ImageGenerationClient.ImageGenerationSettings(1792, 1024, null, "dall-e-3", null));

        server.verify();
        assertFalse(image.usage().hasTokenCounts(), "DALL-E reports no tokens, so it is priced per image");
        assertEquals(1792, image.usage().imageWidth());
    }

    @Test
    void imageAuthFailureAndRateLimitMapToTheirExceptions() {
        server.expect(once(), requestTo(BASE_URL + "/v1/images/generations"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThrows(AiAuthenticationException.class, () -> imageClient().generate("a pan"));

        server.reset();
        server.expect(once(), requestTo(BASE_URL + "/v1/images/generations"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"rate_limit_exceeded\"}}"));
        assertThrows(AiRateLimitedException.class, () -> imageClient().generate("a pan"));
    }

    @Test
    void gptImageWithAReferenceUsesTheEditsEndpoint_andDalleIgnoresReferences() throws Exception {
        server.expect(once(), requestTo(BASE_URL + "/v1/images/edits"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andRespond(withSuccess(imageJson(PNG), MediaType.APPLICATION_JSON));
        ImageGenerationClient client = imageClient();
        assertTrue(client.supportsReferenceImages());
        ImageGenerationClient.GeneratedImage edited = client.generate("make the onions golden",
                List.of(new ImageGenerationClient.ReferenceImage("image/png", PNG)),
                new ImageGenerationClient.ImageGenerationSettings(1024, 1024, null, "gpt-image-1", null));
        server.verify();
        assertArrayEquals(PNG, edited.data());

        server.reset();
        server.expect(once(), requestTo(BASE_URL + "/v1/images/generations"))
                .andRespond(withSuccess(imageJson(PNG), MediaType.APPLICATION_JSON));
        client.generate("a pan", List.of(new ImageGenerationClient.ReferenceImage("image/png", PNG)),
                new ImageGenerationClient.ImageGenerationSettings(1024, 1024, null, "dall-e-3", null));
        server.verify();
        assertEquals("/v1/images/edits", OpenAIImageClient.editsEndpoint("/v1/images/generations"));
    }

    // ---- speech ----

    private OpenAITtsProvider tts() {
        return new OpenAITtsProvider(builder.build(), properties);
    }

    @Test
    void speechSendsModelVoiceAndMp3AndReturnsRawAudio() {
        server.expect(once(), requestTo(BASE_URL + "/v1/audio/speech"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value("tts-1"))
                .andExpect(jsonPath("$.input").value("Chop the onions."))
                .andExpect(jsonPath("$.voice").value("nova"))
                .andExpect(jsonPath("$.response_format").value("mp3"))
                .andExpect(jsonPath("$.instructions").doesNotExist())
                .andRespond(withSuccess(MP3, MediaType.parseMediaType("audio/mpeg")));

        GeneratedAudio audio = tts().synthesize(new TtsRequest("Chop the onions.", "tts-1", "nova", null, null));

        server.verify();
        assertArrayEquals(MP3, audio.data());
        assertEquals(AudioFormat.MP3, audio.format());
        assertEquals("audio/mpeg", audio.mimeType());
        assertNull(audio.durationMs());
        assertEquals("nova", audio.voice());
    }

    @Test
    void speechFallsBackToTheConfiguredVoiceWhenTheNarrationVoiceIsNotOpenAis() {
        assertEquals("alloy", OpenAITtsProvider.resolveVoice("Kore", "alloy"));
        assertEquals("alloy", OpenAITtsProvider.resolveVoice(null, "alloy"));
        assertEquals("shimmer", OpenAITtsProvider.resolveVoice("Shimmer", "alloy"));
    }

    @Test
    void speechSendsInstructionsOnlyToGpt4oModels() {
        properties.getTts().setInstructions("Warm and slow");
        assertEquals("Warm and slow",
                tts().buildRequestPayload("t", "gpt-4o-mini-tts", "alloy", AudioFormat.MP3).get("instructions"));
        assertNull(tts().buildRequestPayload("t", "tts-1", "alloy", AudioFormat.MP3).get("instructions"));
    }

    @Test
    void speechRateLimitAndAuthMapToTheirExceptions() {
        server.expect(once(), requestTo(BASE_URL + "/v1/audio/speech"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "3"));
        AiRateLimitedException limited = assertThrows(AiRateLimitedException.class,
                () -> tts().synthesize(new TtsRequest("hello", null, null, null, null)));
        assertEquals(Duration.ofSeconds(3), limited.getRetryAfter());

        server.reset();
        server.expect(once(), requestTo(BASE_URL + "/v1/audio/speech"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThrows(AiAuthenticationException.class,
                () -> tts().synthesize(new TtsRequest("hello", null, null, null, null)));
    }

    @Test
    void missingApiKeyIsAConfigurationError() {
        properties.setApiKey("");
        assertThrows(AiCommunicationException.class, () -> imageClient().generate("a pan"));
        assertThrows(AiCommunicationException.class,
                () -> tts().synthesize(new TtsRequest("hello", null, null, null, null)));
    }
}
