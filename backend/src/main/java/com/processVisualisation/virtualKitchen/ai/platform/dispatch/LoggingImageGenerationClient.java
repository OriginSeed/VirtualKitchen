package com.processVisualisation.virtualKitchen.ai.platform.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.processVisualisation.virtualKitchen.ai.platform.usage.AiUsageMeter;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * Wraps a provider {@link ImageGenerationClient}: counts each call's token usage against the running AI request
 * (so image cost is priced and persisted like text cost), and writes one DEBUG line per provider call. The
 * operator-facing summary — model, tokens, cost, duration — is {@code ai_request_completed}, written once per
 * request by {@code AiRequestQueueService}. The prompt is never logged.
 */
class LoggingImageGenerationClient implements ImageGenerationClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingImageGenerationClient.class);

    private final ImageGenerationClient delegate;
    private final String provider;
    private final String model;

    LoggingImageGenerationClient(ImageGenerationClient delegate, String provider, String model) {
        this.delegate = delegate;
        this.provider = provider;
        this.model = model;
    }

    @Override
    public GeneratedImage generate(String prompt) throws JsonProcessingException {
        return generate(prompt, List.of());
    }

    @Override
    public boolean supportsReferenceImages() {
        return delegate.supportsReferenceImages();
    }

    @Override
    public GeneratedImage generate(String prompt, List<ReferenceImage> references) throws JsonProcessingException {
        return generate(prompt, references, ImageGenerationSettings.NONE);
    }

    @Override
    public GeneratedImage generate(String prompt, List<ReferenceImage> references, ImageGenerationSettings settings)
            throws JsonProcessingException {
        long startedAt = System.nanoTime();
        Instant startedAtInstant = Instant.now();
        int promptChars = prompt == null ? 0 : prompt.length();
        ImageGenerationSettings requested = settings == null ? ImageGenerationSettings.NONE : settings;
        try {
            // Without settings, a call without references stays a plain generate(prompt), which every provider
            // implements itself; with settings the provider decides which of them it supports.
            GeneratedImage image;
            if (!requested.isNone()) {
                image = delegate.generate(prompt, references == null ? List.of() : references, requested);
            } else if (references == null || references.isEmpty()) {
                image = delegate.generate(prompt);
            } else {
                image = delegate.generate(prompt, references);
            }
            AiUsageMeter.record(image == null ? null : image.usage());
            AiUsageMeter.recordCall(AiCallRecords.withUsage(
                            AiCallRecords.started("IMAGE_GENERATION", provider, model, startedAtInstant, startedAt),
                            image == null ? null : image.usage())
                    .success(true)
                    .inputChars(promptChars)
                    .outputBytes(image == null || image.data() == null ? null : (long) image.data().length)
                    .build());
            log.debug("event=image_generation_completed provider={} model={} durationMs={} promptChars={} references={} "
                            + "requestedWidth={} requestedHeight={} requestedSteps={} bytes={} mimeType={}",
                    provider, model, LoggingAiClient.elapsedMs(startedAt), prompt == null ? 0 : prompt.length(),
                    references == null ? 0 : references.size(),
                    requested.width(), requested.height(), requested.steps(),
                    image == null || image.data() == null ? 0 : image.data().length,
                    image == null ? null : image.mimeType());
            return image;
        } catch (JsonProcessingException | RuntimeException ex) {
            AiUsageMeter.recordCall(AiCallRecords.failed(
                    AiCallRecords.started("IMAGE_GENERATION", provider, model, startedAtInstant, startedAt)
                            .inputChars(promptChars), ex));
            log.debug("event=image_generation_failed provider={} model={} durationMs={} errorType={} status={}",
                    provider, model, LoggingAiClient.elapsedMs(startedAt), ex.getClass().getSimpleName(),
                    LoggingAiClient.httpStatusOf(ex));
            throw ex;
        }
    }
}
