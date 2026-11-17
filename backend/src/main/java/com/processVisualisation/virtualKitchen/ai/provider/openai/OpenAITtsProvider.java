package com.processVisualisation.virtualKitchen.ai.provider.openai;

import com.processVisualisation.virtualKitchen.ai.provider.ProviderUsage;
import com.processVisualisation.virtualKitchen.ai.provider.config.OpenAIProperties;
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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI text-to-speech ({@code tts-1}, {@code tts-1-hd}, {@code gpt-4o-mini-tts}) through
 * {@code /v1/audio/speech}, which answers with raw audio bytes. MP3 is the default; narration also plays WAV and
 * Opus-in-Ogg. The model is chosen per request, so one bean serves several registry entries.
 * <p>
 * The narration voice is shared by every provider, so a voice that is not one of OpenAI's built-in voices falls
 * back to {@code ai.openai.tts.voice}. Usage is {@link ProviderUsage#NONE}; the duration is known for WAV only.
 */
@Component("openAiTtsProvider")
public class OpenAITtsProvider implements TtsProvider {

    static final Set<String> BUILT_IN_VOICES = Set.of("alloy", "ash", "ballad", "coral", "echo", "fable", "nova",
            "onyx", "sage", "shimmer", "verse");

    private final RestClient restClient;
    private final OpenAIProperties properties;

    public OpenAITtsProvider(@Qualifier("openAiRestClient") RestClient restClient, OpenAIProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public String providerName() {
        return "openai";
    }

    /**
     * @throws AiAuthenticationException  if OpenAI rejects the API key (401/403)
     * @throws AiRateLimitedException     on 429/503, with OpenAI's {@code Retry-After} when given
     * @throws AiCommunicationException   on any other HTTP error, spent credits, or missing configuration
     * @throws AiTimeoutException         if OpenAI is unreachable or too slow
     * @throws AiInvalidResponseException if the answer carries no audio
     */
    @Override
    public GeneratedAudio synthesize(TtsRequest request) {
        OpenAIProperties.Tts tts = properties.getTts();
        String model = StringUtils.hasText(request.providerModelId()) ? request.providerModelId() : tts.getModel();
        String voice = resolveVoice(request.voice(), tts.getVoice());
        AudioFormat format = audioFormat(request.preferredFormat(), tts.getResponseFormat());
        validate(request.text(), model);
        try {
            ResponseEntity<byte[]> response = restClient.post()
                    .uri(tts.getEndpoint())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildRequestPayload(request.text(), model, voice, format))
                    .retrieve()
                    .toEntity(byte[].class);
            byte[] audio = response.getBody();
            if (audio == null || audio.length == 0) {
                throw new AiInvalidResponseException("OpenAI returned no audio");
            }
            Long durationMs = format == AudioFormat.WAV ? WavAudio.durationMs(audio) : null;
            return new GeneratedAudio(audio, format.mimeType(), format, durationMs, voice, model, ProviderUsage.NONE);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new AiAuthenticationException("OpenAI rejected the API key (" + status + ")", ex);
            }
            if (status == 429 && ex.getResponseBodyAsString().contains("insufficient_quota")) {
                throw new AiCommunicationException("OpenAI credits exhausted (insufficient_quota)", ex);
            }
            if (status == 429 || status == 503) {
                throw new AiRateLimitedException("OpenAI speech is rate limited (" + status + ")",
                        OpenAIClient.retryAfter(ex), ex);
            }
            throw new AiCommunicationException("OpenAI speech request failed (" + status + ")", ex);
        } catch (ResourceAccessException ex) {
            throw new AiTimeoutException("OpenAI speech request timed out or could not connect", ex);
        } catch (IllegalArgumentException ex) {
            throw new AiCommunicationException("OpenAI speech endpoint configuration is invalid", ex);
        }
    }

    Map<String, Object> buildRequestPayload(String text, String model, String voice, AudioFormat format) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("input", text);
        payload.put("voice", voice);
        payload.put("response_format", switch (format) {
            case WAV -> "wav";
            case MP3 -> "mp3";
            case OGG_OPUS -> "opus";
        });
        String instructions = properties.getTts().getInstructions();
        // Only gpt-4o-mini-tts takes instructions; tts-1 rejects the field.
        if (StringUtils.hasText(instructions) && model.toLowerCase(Locale.ROOT).contains("gpt-4o")) {
            payload.put("instructions", instructions.trim());
        }
        return payload;
    }

    /** The requested voice when OpenAI has it, otherwise the configured default. */
    static String resolveVoice(String requested, String configured) {
        if (StringUtils.hasText(requested) && BUILT_IN_VOICES.contains(requested.trim().toLowerCase(Locale.ROOT))) {
            return requested.trim().toLowerCase(Locale.ROOT);
        }
        return StringUtils.hasText(configured) ? configured.trim().toLowerCase(Locale.ROOT) : "alloy";
    }

    private static AudioFormat audioFormat(AudioFormat preferred, String configured) {
        if (preferred != null) {
            return preferred;
        }
        String value = configured == null ? "mp3" : configured.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "wav" -> AudioFormat.WAV;
            case "opus" -> AudioFormat.OGG_OPUS;
            default -> AudioFormat.MP3;
        };
    }

    private void validate(String text, String model) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new AiCommunicationException("OpenAI API key is not configured");
        }
        if (!StringUtils.hasText(properties.getTts().getEndpoint()) || !StringUtils.hasText(model)) {
            throw new AiCommunicationException("OpenAI speech endpoint or model is not configured");
        }
        if (!StringUtils.hasText(text)) {
            throw new AiCommunicationException("Narration text is empty");
        }
    }
}
