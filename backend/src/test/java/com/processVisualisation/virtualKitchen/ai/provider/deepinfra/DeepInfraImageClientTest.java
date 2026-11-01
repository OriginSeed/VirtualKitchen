package com.processVisualisation.virtualKitchen.ai.provider.deepinfra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient.GeneratedImage;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient.ReferenceImage;
import com.processVisualisation.virtualKitchen.ai.provider.config.DeepInfraProperties;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * DeepInfra's OpenAI-compatible image generation: request mapping, {@code data[0].b64_json} decoding, MIME detection
 * and error mapping, all against a mocked HTTP server (never the real API).
 */
class DeepInfraImageClientTest {

    private static final String BASE_URL = "https://deepinfra.example";
    private static final String IMAGES_URL = BASE_URL + "/v1/openai/images/generations";
    private static final String MODEL = "black-forest-labs/FLUX-2-klein-4b";
    private static final String API_KEY = "sentinel-deepinfra-key-31337";
    private static final String PROMPT = "Onions being chopped on a wooden cutting board";

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};

    private DeepInfraProperties properties;
    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        properties = new DeepInfraProperties();
        properties.setApiKey(API_KEY);
        properties.setBaseUrl(BASE_URL);
        properties.getImage().setModel(MODEL);

        builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    void successfulRequestSendsEndpointAuthModelPromptAndSize() {
        server.expect(once(), requestTo(IMAGES_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.prompt").value(PROMPT))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.size").value("1024x1024"))
                .andExpect(jsonPath("$.n").value(1))
                .andExpect(jsonPath("$.response_format").value("b64_json"))
                .andRespond(imageResponse(JPEG));

        GeneratedImage image = client().generate(PROMPT);

        server.verify();
        assertArrayEquals(JPEG, image.data());
        assertEquals("image/jpeg", image.mimeType());
        assertEquals(1, image.usage().imageCount());
    }

    @Test
    void configuredModelDimensionsAndCountAreSent() {
        properties.getImage().setModel("black-forest-labs/other");
        properties.getImage().setWidth(768);
        properties.getImage().setHeight(512);
        properties.getImage().setCount(2);
        server.expect(once(), requestTo(IMAGES_URL))
                .andExpect(jsonPath("$.model").value("black-forest-labs/other"))
                .andExpect(jsonPath("$.size").value("768x512"))
                .andExpect(jsonPath("$.n").value(2))
                .andRespond(withSuccess("""
                        {"created": 1707000000, "data": [{"b64_json": "%s"}, {"b64_json": "%s"}]}
                        """.formatted(b64(PNG), b64(JPEG)), MediaType.APPLICATION_JSON));

        GeneratedImage image = client().generate(PROMPT);

        server.verify();
        assertArrayEquals(PNG, image.data(), "the first image is used");
        assertEquals(768, image.usage().imageWidth());
        assertEquals(512, image.usage().imageHeight());
    }

    @Test
    void mimeTypeComesFromTheDecodedBytesNotAnAssumption() {
        server.expect(once(), requestTo(IMAGES_URL)).andRespond(imageResponse(PNG));
        server.expect(once(), requestTo(IMAGES_URL)).andRespond(imageResponse(WEBP));

        assertEquals("image/png", client().generate(PROMPT).mimeType());
        assertEquals("image/webp", client().generate(PROMPT).mimeType());
    }

    @Test
    void dataUriPrefixIsStrippedBeforeDecoding() {
        server.expect(once(), requestTo(IMAGES_URL)).andRespond(withSuccess("""
                {"data": [{"b64_json": "data:image/png;base64,%s"}]}
                """.formatted(b64(JPEG)), MediaType.APPLICATION_JSON));

        GeneratedImage image = client().generate(PROMPT);

        assertArrayEquals(JPEG, image.data());
        assertEquals("image/jpeg", image.mimeType(), "the bytes win over a mislabelled data URI");
    }

    @Test
    void unrecognisedBytesFallBackToTheDeclaredImageTypeOnly() {
        byte[] unknown = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        server.expect(once(), requestTo(IMAGES_URL)).andRespond(withSuccess("""
                {"data": [{"b64_json": "data:image/avif;base64,%s"}]}
                """.formatted(b64(unknown)), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(IMAGES_URL)).andRespond(imageResponse(unknown));

        assertEquals("image/avif", client().generate(PROMPT).mimeType());
        assertThrows(AiInvalidResponseException.class, () -> client().generate(PROMPT));
    }

    @Test
    void missingOrEmptyImageDataIsAnInvalidResponse() {
        server.expect(once(), requestTo(IMAGES_URL)).andRespond(withSuccess("{\"data\": []}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(IMAGES_URL)).andRespond(withSuccess("{\"data\": [{\"b64_json\": \"\"}]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(IMAGES_URL)).andRespond(withSuccess("{\"data\": [{\"url\": \"https://x/img\"}]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(IMAGES_URL)).andRespond(withSuccess("{\"images\": [\"" + b64(PNG) + "\"]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(IMAGES_URL)).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        for (int i = 0; i < 5; i++) {
            assertThrows(AiInvalidResponseException.class, () -> client().generate(PROMPT));
        }
        server.verify();
    }

    @Test
    void invalidBase64AndMalformedJsonAreInvalidResponses() {
        server.expect(once(), requestTo(IMAGES_URL)).andRespond(withSuccess("{\"data\": [{\"b64_json\": \"not*base64!\"}]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(IMAGES_URL)).andRespond(withSuccess("{not json", MediaType.APPLICATION_JSON));

        AiInvalidResponseException invalid = assertThrows(AiInvalidResponseException.class, () -> client().generate(PROMPT));
        assertTrue(invalid.getMessage().contains("base64"));
        assertThrows(AiInvalidResponseException.class, () -> client().generate(PROMPT));
    }

    @Test
    void unauthorizedAndForbiddenAreAuthenticationFailures() {
        server.expect(once(), requestTo(IMAGES_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(once(), requestTo(IMAGES_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertKeyNotExposed(assertThrows(AiAuthenticationException.class, () -> client().generate(PROMPT)));
        assertThrows(AiAuthenticationException.class, () -> client().generate(PROMPT));
    }

    @Test
    void rateLimitIsRetryableAndCarriesRetryAfter() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "12");
        server.expect(once(), requestTo(IMAGES_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));
        server.expect(once(), requestTo(IMAGES_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        AiRateLimitedException limited = assertThrows(AiRateLimitedException.class, () -> client().generate(PROMPT));
        assertEquals(Duration.ofSeconds(12), limited.getRetryAfter());
        assertNull(assertThrows(AiRateLimitedException.class, () -> client().generate(PROMPT)).getRetryAfter());
    }

    @Test
    void serverErrorsAreCommunicationFailuresAndTimeoutsAreTimeouts() {
        server.expect(once(), requestTo(IMAGES_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(once(), requestTo(IMAGES_URL)).andRespond(request -> {
            throw new SocketTimeoutException("Read timed out");
        });

        AiCommunicationException serverError = assertThrows(AiCommunicationException.class, () -> client().generate(PROMPT));
        assertFalse(serverError instanceof AiRateLimitedException);
        assertTrue(serverError.getMessage().contains("500"));
        assertKeyNotExposed(assertThrows(AiTimeoutException.class, () -> client().generate(PROMPT)));
    }

    @Test
    void missingConfigurationFailsWithoutCallingDeepInfra() {
        properties.setApiKey(" ");
        assertTrue(assertThrows(AiCommunicationException.class, () -> client().generate(PROMPT))
                .getMessage().contains("API key is not configured"));

        properties.setApiKey(API_KEY);
        properties.getImage().setModel(null);
        assertThrows(AiCommunicationException.class, () -> client().generate(PROMPT));

        properties.getImage().setModel(MODEL);
        properties.getImage().setCount(0);
        assertThrows(AiCommunicationException.class, () -> client().generate(PROMPT));

        properties.getImage().setCount(1);
        assertThrows(AiCommunicationException.class, () -> client().generate(" "));
        server.verify();
    }

    @Test
    void referenceImagesAreNotSupportedAndAreIgnored() throws Exception {
        server.expect(once(), requestTo(IMAGES_URL))
                .andExpect(jsonPath("$.image").doesNotExist())
                .andRespond(imageResponse(PNG));

        DeepInfraImageClient client = client();
        assertFalse(client.supportsReferenceImages());
        assertEquals("image/png", client.generate(PROMPT, List.of(new ReferenceImage("image/png", PNG))).mimeType());
        server.verify();
    }

    private DeepInfraImageClient client() {
        return new DeepInfraImageClient(builder.build(), properties, new ObjectMapper());
    }

    private static org.springframework.test.web.client.ResponseCreator imageResponse(byte[] image) {
        return withSuccess("""
                {"created": 1707000000, "data": [{"revised_prompt": "%s", "b64_json": "%s"}]}
                """.formatted(PROMPT, b64(image)), MediaType.APPLICATION_JSON);
    }

    private static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private static void assertKeyNotExposed(AiClientException ex) {
        assertFalse(String.valueOf(ex.getMessage()).contains(API_KEY), "exception message must not expose the API key");
    }
}
