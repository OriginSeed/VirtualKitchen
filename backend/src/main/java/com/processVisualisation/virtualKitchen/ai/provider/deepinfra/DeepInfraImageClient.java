package com.processVisualisation.virtualKitchen.ai.provider.deepinfra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient;
import com.processVisualisation.virtualKitchen.ai.provider.ProviderUsage;
import com.processVisualisation.virtualKitchen.ai.provider.config.DeepInfraProperties;
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
import java.util.List;
import java.util.Map;

/**
 * {@link ImageGenerationClient} implementation that talks to DeepInfra's
 * OpenAI-compatible image generation API (e.g. FLUX.2 Klein 4B). Reuses the
 * DeepInfra API key, base URL and REST client of the text client; only the
 * {@code ai.deepinfra.image.*} settings are image-specific. Registered under
 * the bean name {@code deepInfraImageClient} so the model registry can route
 * to it alongside every other image provider bean. Prompt-only: reference
 * images are ignored.
 */
@Component("deepInfraImageClient")
public class DeepInfraImageClient implements ImageGenerationClient {

    private final RestClient restClient;
    private final DeepInfraProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Creates a client bound to the shared DeepInfra REST client and configuration.
     *
     * @param restClient the pre-configured REST client used to call the DeepInfra API
     * @param properties the configured DeepInfra API key and image settings
     * @param objectMapper the JSON mapper used to parse DeepInfra responses
     */
    public DeepInfraImageClient(
            @Qualifier("deepInfraRestClient") RestClient restClient,
            DeepInfraProperties properties,
            ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Generates an image for the given prompt and decodes the first returned image ({@code data[0].b64_json}).
     *
     * @param prompt the text prompt describing the image to generate
     * @return the generated image bytes, with the MIME type detected from those bytes
     * @throws AiAuthenticationException if DeepInfra rejects the API key (401/403)
     * @throws AiRateLimitedException if DeepInfra throttles the request (429) or the model is overloaded (503)
     * @throws AiCommunicationException if DeepInfra is not configured or returns any other HTTP error
     * @throws AiTimeoutException if the request times out or the host cannot be reached
     * @throws AiInvalidResponseException if the response is malformed, has no image, or the image is not decodable
     */
    @Override
    public GeneratedImage generate(String prompt) {
        return generate(prompt, List.of(), ImageGenerationSettings.NONE);
    }

    /**
     * Same as {@link #generate(String)}, with the settings' width and height replacing the configured size where
     * set. FLUX.2 Klein on DeepInfra documents no step-count parameter, so {@code settings.steps()} is never sent;
     * reference images are ignored.
     */
    @Override
    public GeneratedImage generate(String prompt, List<ReferenceImage> references, ImageGenerationSettings settings) {
        ImageGenerationSettings requested = settings == null ? ImageGenerationSettings.NONE : settings;
        DeepInfraProperties.Image image = properties.getImage();
        Integer width = requested.width() != null ? requested.width() : image.getWidth();
        Integer height = requested.height() != null ? requested.height() : image.getHeight();
        validate(prompt, width, height);
        try {
            String response = restClient.post()
                    .uri(image.getEndpoint())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(buildRequestPayload(prompt, width, height))
                    .retrieve()
                    .body(String.class);

            return parseImage(response, width, height);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new AiAuthenticationException("DeepInfra image authentication failed", ex);
            }
            if (status == 429 || status == 503) {
                String reason = status == 429 ? "rate limited (429)" : "model overloaded (503)";
                throw new AiRateLimitedException("DeepInfra image " + reason, DeepInfraClient.retryAfter(ex), ex);
            }
            throw new AiCommunicationException("DeepInfra image request failed with status: " + ex.getStatusCode(), ex);
        } catch (ResourceAccessException ex) {
            throw new AiTimeoutException("DeepInfra image request timed out or could not connect", ex);
        } catch (JsonProcessingException ex) {
            throw new AiInvalidResponseException("Failed to process DeepInfra image JSON payload", ex);
        } catch (IllegalArgumentException ex) {
            throw new AiCommunicationException("DeepInfra image endpoint configuration is invalid", ex);
        }
    }

    Map<String, Object> buildRequestPayload(String prompt, int width, int height) {
        DeepInfraProperties.Image image = properties.getImage();
        return Map.of(
                "prompt", prompt,
                "model", image.getModel(),
                "size", width + "x" + height,
                "n", image.getCount(),
                "response_format", "b64_json"
        );
    }

    private GeneratedImage parseImage(String response, int width, int height) throws JsonProcessingException {
        if (!StringUtils.hasText(response)) {
            throw new AiInvalidResponseException("Received empty response from DeepInfra image generation");
        }
        JsonNode first = objectMapper.readTree(response).path("data").path(0);
        String base64 = first.path("b64_json").asText(null);
        if (!StringUtils.hasText(base64)) {
            throw new AiInvalidResponseException("DeepInfra image response did not contain an image");
        }

        String declaredMimeType = null;
        if (base64.startsWith("data:")) {
            // data:image/jpeg;base64,... : keep the declared type as a fallback, decode what follows the comma.
            int commaIndex = base64.indexOf(',');
            int semicolonIndex = base64.indexOf(';');
            if (semicolonIndex > 5 && semicolonIndex < commaIndex) {
                declaredMimeType = base64.substring(5, semicolonIndex);
            }
            base64 = commaIndex > 0 ? base64.substring(commaIndex + 1) : "";
        }

        byte[] data;
        try {
            // Strict decoder (the MIME decoder silently skips invalid characters); only line breaks are tolerated.
            data = Base64.getDecoder().decode(base64.replaceAll("\\s", "").getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException ex) {
            throw new AiInvalidResponseException("Invalid base64 image returned by DeepInfra");
        }
        if (data.length == 0) {
            throw new AiInvalidResponseException("DeepInfra returned an empty image");
        }

        String mimeType = detectMimeType(data);
        if (mimeType == null) {
            if (declaredMimeType == null || !declaredMimeType.startsWith("image/")) {
                throw new AiInvalidResponseException("DeepInfra returned data that is not a recognised image format");
            }
            mimeType = declaredMimeType;
        }
        return new GeneratedImage(mimeType, data, ProviderUsage.NONE.withImage(1, width, height));
    }

    /** The image type from the file signature (PNG, JPEG, WebP, GIF), or null when it is none of these. */
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
        if (startsWith(data, 0, 'G', 'I', 'F', '8')) {
            return MediaType.IMAGE_GIF_VALUE;
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

    private void validate(String prompt, Integer width, Integer height) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new AiCommunicationException("DeepInfra API key is not configured");
        }
        DeepInfraProperties.Image image = properties.getImage();
        if (!StringUtils.hasText(image.getEndpoint()) || !StringUtils.hasText(image.getModel())) {
            throw new AiCommunicationException("DeepInfra image endpoint or model is not configured");
        }
        if (width == null || height == null || width < 1 || height < 1) {
            throw new AiCommunicationException("DeepInfra image width and height must be positive");
        }
        if (image.getCount() == null || image.getCount() < 1) {
            throw new AiCommunicationException("DeepInfra image count must be at least 1");
        }
        if (!StringUtils.hasText(prompt)) {
            throw new AiCommunicationException("Image prompt cannot be empty");
        }
    }
}
