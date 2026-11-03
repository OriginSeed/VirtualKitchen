package com.processVisualisation.virtualKitchen.ai.provider.deepinfra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.provider.ProviderUsage;
import com.processVisualisation.virtualKitchen.ai.provider.config.DeepInfraProperties;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiAuthenticationException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiCommunicationException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiInvalidResponseException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiRateLimitedException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiTimeoutException;
import com.processVisualisation.virtualKitchen.ai.provider.tts.AudioFormat;
import com.processVisualisation.virtualKitchen.ai.provider.tts.GeneratedAudio;
import com.processVisualisation.virtualKitchen.ai.provider.tts.TtsRequest;
import com.processVisualisation.virtualKitchen.ai.provider.tts.WavAudio;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * DeepInfra speech (MiMo-V2.5-TTS): request mapping and every response shape the endpoint might use, against a
 * mocked HTTP server (never the real API).
 */
class DeepInfraTtsProviderTest {

    private static final String BASE_URL = "https://deepinfra.example";
    private static final String SPEECH_URL = BASE_URL + "/v1/audio/speech";
    private static final String API_KEY = "sentinel-deepinfra-key-31337";
    private static final String TEXT = "Chop the onions finely.";

    /** One second of 24 kHz mono 16-bit silence. */
    private static final byte[] PCM = new byte[48_000];
    private static final byte[] WAV = WavAudio.fromPcm(PCM, 24_000, 1, 16);
    private static final byte[] MP3 = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFB};

    private DeepInfraProperties properties;
    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        properties = new DeepInfraProperties();
        properties.setApiKey(API_KEY);
        properties.setBaseUrl(BASE_URL);
        builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    void sendsModelTextVoiceAndFormatAndReadsRawWav() {
        server.expect(once(), requestTo(SPEECH_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value("XiaomiMiMo/MiMo-V2.5-tts"))
                .andExpect(jsonPath("$.input").value(TEXT))
                .andExpect(jsonPath("$.voice").value("Mia"))
                .andExpect(jsonPath("$.response_format").value("wav"))
                .andExpect(jsonPath("$.extra_body").doesNotExist())
                .andRespond(withSuccess(WAV, MediaType.parseMediaType("audio/wav")));

        GeneratedAudio audio = provider().synthesize(request(null, null));

        server.verify();
        assertArrayEquals(WAV, audio.data());
        assertEquals(AudioFormat.WAV, audio.format());
        assertEquals(1000L, audio.durationMs());
        assertEquals("Mia", audio.voice());
        assertEquals(ProviderUsage.NONE, audio.usage(), "billed per audio second, so no token counts");
    }

    @Test
    void theSelectedModelVoiceAndAStyleAreSent() {
        properties.getTts().setStylePrompt("Warm and calm, like a cooking show host.");
        server.expect(once(), requestTo(SPEECH_URL))
                .andExpect(jsonPath("$.model").value("XiaomiMiMo/Other-tts"))
                .andExpect(jsonPath("$.voice").value("Dean"))
                .andExpect(jsonPath("$.extra_body.instructions").value("Warm and calm, like a cooking show host."))
                .andRespond(withSuccess(WAV, MediaType.parseMediaType("audio/wav")));

        provider().synthesize(request("XiaomiMiMo/Other-tts", "Dean"));

        server.verify();
    }

    @Test
    void readsRawMp3WithoutADuration() {
        server.expect(once(), requestTo(SPEECH_URL))
                .andRespond(withSuccess(MP3, MediaType.APPLICATION_OCTET_STREAM));

        GeneratedAudio audio = provider().synthesize(request(null, null));

        assertEquals(AudioFormat.MP3, audio.format());
        assertEquals("audio/mpeg", audio.mimeType());
        assertNull(audio.durationMs());
    }

    @Test
    void readsBase64AudioFromAJsonDataUrl() {
        String body = "{\"audio\": \"data:audio/wav;base64," + Base64.getEncoder().encodeToString(WAV) + "\"}";
        server.expect(once(), requestTo(SPEECH_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        GeneratedAudio audio = provider().synthesize(request(null, null));

        assertArrayEquals(WAV, audio.data());
        assertEquals(1000L, audio.durationMs());
    }

    @Test
    void readsBase64AudioFromAChatStyleResponse() {
        String body = "{\"choices\": [{\"message\": {\"audio\": {\"data\": \"" + Base64.getEncoder().encodeToString(MP3) + "\"}}}]}";
        server.expect(once(), requestTo(SPEECH_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertEquals(AudioFormat.MP3, provider().synthesize(request(null, null)).format());
    }

    @Test
    void wrapsRawPcmAsWav() {
        server.expect(once(), requestTo(SPEECH_URL)).andRespond(withSuccess(PCM, MediaType.parseMediaType("audio/pcm")));

        GeneratedAudio audio = provider().synthesize(request(null, null));

        assertEquals(AudioFormat.WAV, audio.format());
        assertEquals(1000L, audio.durationMs());
        assertEquals(PCM.length + 44, audio.data().length);
    }

    @Test
    void emptyOrUnplayableAudioIsAnInvalidResponse() {
        server.expect(requestTo(SPEECH_URL)).andRespond(withSuccess("{\"status\": \"ok\"}", MediaType.APPLICATION_JSON));
        assertThrows(AiInvalidResponseException.class, () -> provider().synthesize(request(null, null)));

        server.reset();
        server.expect(requestTo(SPEECH_URL)).andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.parseMediaType("audio/flac")));
        assertThrows(AiInvalidResponseException.class, () -> provider().synthesize(request(null, null)));
    }

    @Test
    void mapsAuthRateLimitAndTimeoutErrors() {
        server.expect(requestTo(SPEECH_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThrows(AiAuthenticationException.class, () -> provider().synthesize(request(null, null)));

        server.reset();
        HttpHeaders retry = new HttpHeaders();
        retry.set(HttpHeaders.RETRY_AFTER, "7");
        server.expect(requestTo(SPEECH_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retry));
        AiRateLimitedException limited = assertThrows(AiRateLimitedException.class, () -> provider().synthesize(request(null, null)));
        assertEquals(Duration.ofSeconds(7), limited.getRetryAfter());

        server.reset();
        server.expect(requestTo(SPEECH_URL)).andRespond(request -> {
            throw new SocketTimeoutException("read timed out");
        });
        assertThrows(AiTimeoutException.class, () -> provider().synthesize(request(null, null)));
    }

    @Test
    void aMissingKeyFailsWithoutCallingDeepInfra() {
        properties.setApiKey("");

        assertThrows(AiCommunicationException.class, () -> provider().synthesize(request(null, null)));
        server.verify();
    }

    private DeepInfraTtsProvider provider() {
        return new DeepInfraTtsProvider(builder.build(), properties, new ObjectMapper());
    }

    private static TtsRequest request(String model, String voice) {
        return new TtsRequest(TEXT, model, voice, "en-US", null);
    }
}
