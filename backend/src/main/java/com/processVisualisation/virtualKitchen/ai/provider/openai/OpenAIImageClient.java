package com.processVisualisation.virtualKitchen.ai.provider.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient;
import com.processVisualisation.virtualKitchen.ai.provider.ProviderUsage;
import com.processVisualisation.virtualKitchen.ai.provider.config.OpenAIProperties;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiAuthenticationException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiCommunicationException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiInvalidResponseException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiRateLimitedException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiTimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link ImageGenerationClient} for OpenAI's image generation API ({@code gpt-image-1}, {@code dall-e-3}, ...).
 * Reuses the OpenAI API key, base URL and REST client of the text client; only {@code ai.openai.image.*} is
 * image-specific. Prompt-only: reference images are ignored, so callers do not chain them.
 * <p>
 * The model is chosen per request (the registry's provider model id), so one bean serves several registry
 * entries. {@code gpt-image-1} always answers with base64 and rejects {@code response_format}; DALL-E answers
 * with a URL unless {@code response_format=b64_json} is sent.
 */
@Component("openAiImageClient")
public class OpenAIImageClient implements ImageGenerationClient {

    private final RestClient restClient;
    private final OpenAIProperties properties;
    private final ObjectMapper objectMapper;

    public OpenAIImageClient(
            @Qualifier("openAiRestClient") RestClient restClient,
            OpenAIProperties properties,
            ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public GeneratedImage generate(String prompt) {
        return generate(prompt, List.of(), ImageGenerationSettings.NONE);
    }

    /**
     * Generates one image. The settings' width and height replace the configured size when both are set;
     * step count and seed are not supported by OpenAI and are ignored.
     *
     * @throws AiAuthenticationException if OpenAI rejects the API key (401/403)
     * @throws AiRateLimitedException if OpenAI throttles the request (429, except spent credits) or is overloaded (503)
     * @throws AiCommunicationException if OpenAI is not configured or returns any other HTTP error
     * @throws AiTimeoutException if the request times out or the host cannot be reached
     * @throws AiInvalidResponseException if the response is malformed or has no decodable image
     */
    @Override
    public GeneratedImage generate(String prompt, List<ReferenceImage> references, ImageGenerationSettings settings) {
        ImageGenerationSettings requested = settings == null ? ImageGenerationSettings.NONE : settings;
        OpenAIProperties.Image image = properties.getImage();
        String model = StringUtils.hasText(requested.modelId()) ? requested.modelId() : image.getModel();
        validate(prompt, model);
        String size = size(requested, image.getSize());
        try {
            String response = restClient.post()
                    .uri(image.getEndpoint())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(buildRequestPayload(prompt, model, size))
                    .retrieve()
                    .body(String.class);
            return parseImage(response, size);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new AiAuthenticationException("OpenAI image authentication failed", ex);
            }
            if (status == 429 && ex.getResponseBodyAsString().contains("insufficient_quota")) {
                throw new AiCommunicationException("OpenAI credits exhausted (insufficient_quota)", ex);
            }
            if (status == 429 || status == 503) {
                String reason = status == 429 ? "rate limited (429)" : "overloaded (503)";
                throw new AiRateLimitedException("OpenAI image " + reason, OpenAIClient.retryAfter(ex), ex);
            }
            throw new AiCommunicationException("OpenAI image request failed with status: " + ex.getStatusCode()
                    + errorDetail(ex), ex);
        } catch (ResourceAccessException ex) {
            throw new AiTimeoutException("OpenAI image request timed out or could not connect", ex);
        } catch (JsonProcessingException ex) {
            throw new AiInvalidResponseException("Failed to process OpenAI image JSON payload", ex);
        } catch (IllegalArgumentException ex) {
            throw new AiCommunicationException("OpenAI image endpoint configuration is invalid", ex);
        }
    }

    Map<String, Object> buildRequestPayload(String prompt, String model, String size) {
        OpenAIProperties.Image image = properties.getImage();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("prompt", prompt);
        payload.put("n", 1);
        if (StringUtils.hasText(size)) {
            payload.put("size", size);
        }
        if (StringUtils.hasText(image.getQuality())) {
            payload.put("quality", image.getQuality());
        }
        if (isGptImage(model)) {
            // Always base64; png keeps the grid slicer (ImageIO) working.
            payload.put("output_format", "png");
        } else {
            payload.put("response_format", "b64_json");
        }
        return payload;
    }

    private static boolean isGptImage(String model) {
        return model.toLowerCase(Locale.ROOT).startsWith("gpt-image");
    }

    /** The settings' size when both sides are set, otherwise the configured one. */
    private static String size(ImageGenerationSettings requested, String configured) {
        if (requested.width() != null && requested.height() != null) {
            return requested.width() + "x" + requested.height();
        }
        return configured;
    }

    private GeneratedImage parseImage(String response, String size) throws JsonProcessingException {
        if (!StringUtils.hasText(response)) {
            throw new AiInvalidResponseException("Received empty response from OpenAI image generation");
        }
        JsonNode root = objectMapper.readTree(response);
        String base64 = root.path("data").path(0).path("b64_json").asText(null);
        if (!StringUtils.hasText(base64)) {
            throw new AiInvalidResponseException("OpenAI image response did not contain an image");
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(base64.replaceAll("\\s", "").getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException ex) {
            throw new AiInvalidResponseException("Invalid base64 image returned by OpenAI");
        }
        if (data.length == 0) {
            throw new AiInvalidResponseException("OpenAI returned an empty image");
        }
        String mimeType = detectMimeType(data);
        if (mimeType == null) {
            throw new AiInvalidResponseException("OpenAI returned data that is not a recognised image format");
        }

        Integer width = null;
        Integer height = null;
        int x = size == null ? -1 : size.indexOf('x');
        if (x > 0) {
            try {
                width = Integer.valueOf(size.substring(0, x));
                height = Integer.valueOf(size.substring(x + 1));
            } catch (NumberFormatException ignored) {
                // "auto" and the like: dimensions stay unknown
            }
        }
        JsonNode usage = root.path("usage");
        ProviderUsage providerUsage = usage.isMissingNode() || usage.isNull()
                ? ProviderUsage.NONE
                : new ProviderUsage(readLong(usage, "input_tokens"), readLong(usage, "output_tokens"), null,
                readLong(usage, "total_tokens"));
        return new GeneratedImage(mimeType, data, providerUsage.withImage(1, width, height));
    }

    private static Long readLong(JsonNode parent, String key) {
        JsonNode node = parent.path(key);
        return node.isNumber() ? node.asLong() : null;
    }

    /** OpenAI's error message (e.g. a content-policy refusal), appended to the exception so it shows in logs. */
    private String errorDetail(RestClientResponseException ex) {
        try {
            String message = objectMapper.readTree(ex.getResponseBodyAsString()).path("error").path("message")
                    .asText(null);
            return message == null ? "" : ": " + message;
        } catch (Exception ignored) {
            return "";
        }
    }

    /** The image type from the file signature (PNG, JPEG, WebP), or null when it is none of these. */
    static String detectMimeType(byte[] data) {
        if (startsWith(data, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return MediaType.IMAGE_PNG_VALUE;
        }
        if (startsWith(data, 0, 0xFF, 0xD8, 0xFF)) {
            return MediaType.IMAGE_JPEG_VALUE;
        }
        if (startsWith(data, 0, 'R', 'I', 'F', 'F') && startsWith(data, 8, 'W', 'E', 'B', 'P')) {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] data, int offset, int... signature) {
        if (data.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((data[offset + i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private void validate(String prompt, String model) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new AiCommunicationException("OpenAI API key is not configured");
        }
        if (!StringUtils.hasText(properties.getImage().getEndpoint()) || !StringUtils.hasText(model)) {
            throw new AiCommunicationException("OpenAI image endpoint or model is not configured");
        }
        if (!StringUtils.hasText(prompt)) {
            throw new AiCommunicationException("Image prompt cannot be empty");
        }
    }
}
