package com.processVisualisation.virtualKitchen.ai.provider.deepinfra;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.processVisualisation.virtualKitchen.ai.provider.tts.TtsProvider;
import com.processVisualisation.virtualKitchen.ai.provider.tts.TtsRequest;
import com.processVisualisation.virtualKitchen.ai.provider.tts.WavAudio;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * DeepInfra text-to-speech (e.g. {@code XiaomiMiMo/MiMo-V2.5-tts}) through its OpenAI-style speech endpoint.
 * <p>
 * DeepInfra doesn't document the response body for every speech model, so it is read defensively: raw audio bytes
 * (wav / mp3 / ogg), JSON carrying base64 audio ({@code audio}, optionally a {@code data:} URL, or
 * {@code choices[0].message.audio.data}), or raw PCM, which is wrapped as WAV. Narration can only store and play WAV,
 * MP3 and OGG, so anything else is refused as an invalid response.
 * <p>
 * Billed per audio second (no token counts), so usage is {@link ProviderUsage#NONE} and the duration carries the cost.
 */
@Component("deepInfraTtsProvider")
public class DeepInfraTtsProvider implements TtsProvider {

    private final RestClient restClient;
    private final DeepInfraProperties properties;
    private final ObjectMapper objectMapper;

    public DeepInfraTtsProvider(
            @Qualifier("deepInfraRestClient") RestClient restClient,
            DeepInfraProperties properties,
            ObjectMapper objectMapper
    ) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerName() {
        return "deepinfra";
    }

    /**
     * @throws AiAuthenticationException  if DeepInfra rejects the API key (401/403)
     * @throws AiRateLimitedException     on 429/503, with DeepInfra's {@code Retry-After} when given
     * @throws AiCommunicationException   on any other HTTP error or missing configuration
     * @throws AiTimeoutException         if DeepInfra is unreachable or too slow
     * @throws AiInvalidResponseException if the answer carries no playable audio
     */
    @Override
    public GeneratedAudio synthesize(TtsRequest request) {
        DeepInfraProperties.Tts tts = properties.getTts();
        String model = StringUtils.hasText(request.providerModelId()) ? request.providerModelId() : tts.getModel();
        String voice = StringUtils.hasText(request.voice()) ? request.voice() : tts.getVoice();
        String format = responseFormat(request.preferredFormat(), tts.getResponseFormat());
        validate(request.text(), model);
        try {
            ResponseEntity<byte[]> response = restClient.post()
                    .uri(tts.getEndpoint())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildRequestPayload(request.text(), model, voice, format))
                    .retrieve()
                    .toEntity(byte[].class);
            return parse(response, voice, model);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new AiAuthenticationException("DeepInfra rejected the API key (" + status + ")", ex);
            }
            if (status == 429 || status == 503) {
                throw new AiRateLimitedException("DeepInfra speech is rate limited (" + status + ")",
                        DeepInfraClient.retryAfter(ex), ex);
            }
            throw new AiCommunicationException("DeepInfra speech request failed (" + status + ")", ex);
        } catch (ResourceAccessException ex) {
            throw new AiTimeoutException("DeepInfra speech request timed out or could not connect", ex);
        } catch (IllegalArgumentException ex) {
            throw new AiCommunicationException("DeepInfra speech endpoint configuration is invalid", ex);
        }
    }

    Map<String, Object> buildRequestPayload(String text, String model, String voice, String format) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("input", text);
        if (StringUtils.hasText(voice)) {
            payload.put("voice", voice);
        }
        payload.put("response_format", format);
        String style = properties.getTts().getStylePrompt();
        if (StringUtils.hasText(style)) {
            payload.put("extra_body", Map.of("instructions", style.trim()));
        }
        return payload;
    }

    private GeneratedAudio parse(ResponseEntity<byte[]> response, String voice, String model) {
        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            throw new AiInvalidResponseException("DeepInfra returned no audio");
        }
        MediaType contentType = response.getHeaders().getContentType();
        byte[] audio = isJson(contentType, body) ? audioFromJson(body) : body;
        if (audio.length == 0) {
            throw new AiInvalidResponseException("DeepInfra returned no audio");
        }

        AudioFormat format = sniff(audio);
        if (format == null) {
            // No known signature: raw PCM is the one headerless format a speech endpoint sends (as audio/pcm,
            // audio/L16 or untyped bytes); anything else (flac, …) can't be played by narration.
            String subtype = contentType == null ? "" : contentType.getSubtype().toLowerCase(Locale.ROOT);
            boolean untyped = contentType == null || MediaType.APPLICATION_OCTET_STREAM.includes(contentType);
            boolean pcm = subtype.contains("pcm") || subtype.startsWith("l16") || untyped;
            if (!pcm) {
                throw new AiInvalidResponseException("DeepInfra returned audio in a format narration can't play");
            }
            audio = WavAudio.fromPcm(audio, properties.getTts().getPcmSampleRateHz(), 1, 16);
            format = AudioFormat.WAV;
        }
        Long durationMs = format == AudioFormat.WAV ? WavAudio.durationMs(audio) : null;
        return new GeneratedAudio(audio, format.mimeType(), format, durationMs, voice, model, ProviderUsage.NONE);
    }

    private byte[] audioFromJson(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String encoded = text(root.path("audio"));
            if (encoded == null) {
                encoded = text(root.path("choices").path(0).path("message").path("audio").path("data"));
            }
            if (encoded == null) {
                throw new AiInvalidResponseException("DeepInfra's JSON response carries no audio");
            }
            int comma = encoded.startsWith("data:") ? encoded.indexOf(',') : -1;
            return Base64.getDecoder().decode(comma >= 0 ? encoded.substring(comma + 1) : encoded);
        } catch (IOException | IllegalArgumentException ex) {
            throw new AiInvalidResponseException("DeepInfra's speech response couldn't be read", ex);
        }
    }

    private static String text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static boolean isJson(MediaType contentType, byte[] body) {
        if (contentType != null && contentType.getSubtype().toLowerCase(Locale.ROOT).contains("json")) {
            return true;
        }
        return body.length > 0 && body[0] == '{';
    }

    /** WAV / MP3 / OGG by their file signatures, or null. */
    static AudioFormat sniff(byte[] data) {
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E') {
            return AudioFormat.WAV;
        }
        if (data.length >= 3 && data[0] == 'I' && data[1] == 'D' && data[2] == '3') {
            return AudioFormat.MP3;
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xE0) == 0xE0) {
            return AudioFormat.MP3;
        }
        if (data.length >= 4 && data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S') {
            return AudioFormat.OGG_OPUS;
        }
        return null;
    }

    /** The format to ask for: the caller's preference, else the configured one; only formats narration plays. */
    private static String responseFormat(AudioFormat preferred, String configured) {
        if (preferred != null) {
            return switch (preferred) {
                case WAV -> "wav";
                case MP3 -> "mp3";
                case OGG_OPUS -> "opus";
            };
        }
        String value = configured == null ? "wav" : configured.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "mp3", "opus", "wav" -> value;
            default -> "wav";
        };
    }

    private void validate(String text, String model) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new AiCommunicationException("DeepInfra API key is not configured");
        }
        if (!StringUtils.hasText(properties.getTts().getEndpoint()) || !StringUtils.hasText(model)) {
            throw new AiCommunicationException("DeepInfra speech endpoint or model is not configured");
        }
        if (!StringUtils.hasText(text)) {
            throw new AiCommunicationException("Narration text is empty");
        }
    }
}
