package com.processVisualisation.virtualKitchen.ai.provider.drawthings;

import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient;
import com.processVisualisation.virtualKitchen.ai.provider.ProviderUsage;
import com.processVisualisation.virtualKitchen.ai.provider.config.DrawThingsProperties;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiAuthenticationException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiCommunicationException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiInvalidResponseException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiTimeoutException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ImageGenerationClient} implementation that talks to a self-hosted
 * Draw Things image generation server. It is the default image provider for
 * the Virtual Kitchen application, used to render process-visualization
 * images from text prompts. Registered under the bean name
 * {@code drawThingsImageClient} so it can be selected per-request by the AI
 * model routing layer alongside every other provider bean.
 */
@Component("drawThingsImageClient")
public class DrawThingsImageClient implements ImageGenerationClient {

    private final RestClient restClient;
    private final DrawThingsProperties drawThingsProperties;
    private final ObjectMapper objectMapper;

    /**
     * Creates a client bound to the Draw Things REST client and configuration.
     *
     * @param restClient the pre-configured REST client used to call the Draw Things endpoint
     * @param drawThingsProperties the configured Draw Things endpoint, dimensions, and timeout
     * @param objectMapper the JSON mapper used to parse Draw Things responses
     */
    public DrawThingsImageClient(@Qualifier("drawThingsRestClient") RestClient restClient, DrawThingsProperties drawThingsProperties, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.drawThingsProperties = drawThingsProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Extracts and decodes the first generated image from a raw Draw Things
     * JSON response.
     *
     * @param response the raw JSON response body returned by Draw Things
     * @return the decoded generated image
     * @throws JsonProcessingException if the response body is not valid JSON
     * @throws AiInvalidResponseException if the response contains no image or an invalid/empty base64 payload
     */
    private GeneratedImage parseImage(String response, Integer width, Integer height) throws JsonProcessingException {

        JsonNode root = objectMapper.readTree(response);

        JsonNode images = root.path("images");

        if (!images.isArray() || images.isEmpty()) {
            throw new AiInvalidResponseException(
                    "Draw Things response did not contain an image"
            );
        }

        String base64 = images.get(0).asText();

        if (base64 == null || base64.isBlank()) {
            throw new AiInvalidResponseException(
                    "Draw Things returned an empty image"
            );
        }

        // Handle data:image/png;base64,... if returned
        if (base64.startsWith("data:")) {
            int commaIndex = base64.indexOf(',');

            if (commaIndex > 0) {
                base64 = base64.substring(commaIndex + 1);
            }
        }

        try {
            byte[] imageBytes =
                    Base64.getDecoder().decode(base64);

            return new GeneratedImage(
                    "image/png",
                    imageBytes,
                    ProviderUsage.NONE.withImage(1, width, height)
            );

        } catch (IllegalArgumentException e) {
            throw new AiInvalidResponseException(
                    "Invalid base64 image returned by Draw Things"
            );
        }
    }

    /**
     * Generates an image for the given prompt by calling the configured
     * Draw Things endpoint and decoding the returned base64 image.
     * <p>
     * Transport failures are translated into the typed {@code AiClientException}
     * hierarchy. A self-hosted Draw Things server is the most likely provider to
     * be transiently unreachable, and {@code AiRequestQueueService} only retries
     * an attempt that fails with {@link AiTimeoutException} or
     * {@link AiCommunicationException} — so without this wrapping a restarting
     * server would fail the step outright instead of being retried.
     *
     * @param prompt the text prompt describing the image to generate
     * @return the generated image data and its MIME type
     * @throws AiAuthenticationException if the endpoint rejects the request (401/403)
     * @throws AiCommunicationException if the endpoint returns any other HTTP error, or is misconfigured
     * @throws AiTimeoutException if the request times out or the host cannot be reached
     * @throws AiInvalidResponseException if the response is not valid JSON or contains no usable image
     */
    @Override
    public GeneratedImage generate(String prompt) {
        return generate(prompt, List.of(), ImageGenerationSettings.NONE);
    }

    /**
     * Same as {@link #generate(String)}, with the settings' width, height and step count (A1111 {@code steps})
     * replacing the configured ones where set. Draw Things takes no reference images, so they are ignored.
     */
    @Override
    public GeneratedImage generate(String prompt, List<ReferenceImage> references, ImageGenerationSettings settings) {
        ImageGenerationSettings requested = settings == null ? ImageGenerationSettings.NONE : settings;
        Integer width = requested.width() != null ? requested.width() : drawThingsProperties.getWidth();
        Integer height = requested.height() != null ? requested.height() : drawThingsProperties.getHeight();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("prompt", prompt);
        request.put("width", width);
        request.put("height", height);
        if (requested.steps() != null) {
            request.put("steps", requested.steps());
        }

        try {
            String response = restClient.post()
                    .uri(drawThingsProperties.getEndpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);

            return parseImage(response, width, height);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new AiAuthenticationException("Draw Things authentication failed", ex);
            }
            throw new AiCommunicationException(
                    "Draw Things request failed with status: " + ex.getStatusCode(), ex);
        } catch (ResourceAccessException ex) {
            throw new AiTimeoutException("Draw Things request timed out or could not connect", ex);
        } catch (JsonProcessingException ex) {
            throw new AiInvalidResponseException("Failed to process Draw Things JSON payload", ex);
        } catch (IllegalArgumentException ex) {
            throw new AiCommunicationException("Draw Things endpoint configuration is invalid", ex);
        }
    }
}