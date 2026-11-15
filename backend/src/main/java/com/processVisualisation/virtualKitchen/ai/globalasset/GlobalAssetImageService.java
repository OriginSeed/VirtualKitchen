package com.processVisualisation.virtualKitchen.ai.globalasset;

import com.processVisualisation.virtualKitchen.ai.asset.GlobalAssetStatus;
import com.processVisualisation.virtualKitchen.ai.asset.GlobalResourceType;
import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAsset;
import com.processVisualisation.virtualKitchen.ai.globalasset.dto.GlobalAssetBatchResponseDTO;
import com.processVisualisation.virtualKitchen.ai.globalasset.dto.GlobalAssetImageResponseDTO;
import com.processVisualisation.virtualKitchen.ai.globalasset.dto.GlobalAssetImageResponseDTO.Outcome;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifact;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifactKeyBuilder;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifactService;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifactSpec;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.codec.GeneratedImageCodec;
import com.processVisualisation.virtualKitchen.ai.platform.dispatch.AiClientResolver;
import com.processVisualisation.virtualKitchen.ai.platform.logging.OperationLog;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestOutcome;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestQueueService;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.routing.ModelSelectionOutcome;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient.GeneratedImage;
import com.processVisualisation.virtualKitchen.common.concurrent.NamedTask;
import com.processVisualisation.virtualKitchen.common.concurrent.TaskPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Generates the one reusable image of a global catalog resource (an ingredient, a piece of
 * equipment) for admin requests.
 * <p>
 * Requests are idempotent: a resource that already has an image, or whose generation is already
 * queued or running, never starts another (paid) generation; the atomic claim in
 * {@link GlobalAssetStore} enforces this across concurrent requests. Accepted generations run on
 * the {@code global-asset} {@link TaskPool} through the same AI pipeline as step visualizations:
 * {@link AiRequestQueueService} (model selection, credits, fallback, usage/cost logging) and the
 * AI artifact store, with {@link GlobalAssetImageArtifactConsumer} uploading and publishing the
 * result.
 */
@Service
public class GlobalAssetImageService {

    static final String CORRELATION_TYPE = "global-asset-image";
    static final int MAX_BATCH_IDS = 200;

    private static final Logger log = LoggerFactory.getLogger(GlobalAssetImageService.class);

    private final GlobalResourceResolver resourceResolver;
    private final GlobalAssetStore assetStore;
    private final GlobalAssetPromptBuilder promptBuilder;
    private final AiRequestQueueService queueService;
    private final AiClientResolver clientResolver;
    private final AiArtifactService artifactService;
    private final GeneratedImageCodec generatedImageCodec;
    private final GlobalAssetImageArtifactConsumer imageConsumer;
    private final TaskPool globalAssetTaskPool;
    private final long leaseMs;
    private final Clock clock;

    @Autowired
    public GlobalAssetImageService(
            GlobalResourceResolver resourceResolver,
            GlobalAssetStore assetStore,
            GlobalAssetPromptBuilder promptBuilder,
            AiRequestQueueService queueService,
            AiClientResolver clientResolver,
            AiArtifactService artifactService,
            GeneratedImageCodec generatedImageCodec,
            GlobalAssetImageArtifactConsumer imageConsumer,
            @Qualifier("globalAssetTaskPool") TaskPool globalAssetTaskPool,
            @Value("${app.jobs.stale-after-ms:900000}") long leaseMs) {
        this(resourceResolver, assetStore, promptBuilder, queueService, clientResolver, artifactService,
                generatedImageCodec, imageConsumer, globalAssetTaskPool, leaseMs, Clock.systemUTC());
    }

    GlobalAssetImageService(
            GlobalResourceResolver resourceResolver,
            GlobalAssetStore assetStore,
            GlobalAssetPromptBuilder promptBuilder,
            AiRequestQueueService queueService,
            AiClientResolver clientResolver,
            AiArtifactService artifactService,
            GeneratedImageCodec generatedImageCodec,
            GlobalAssetImageArtifactConsumer imageConsumer,
            TaskPool globalAssetTaskPool,
            long leaseMs,
            Clock clock) {
        this.resourceResolver = resourceResolver;
        this.assetStore = assetStore;
        this.promptBuilder = promptBuilder;
        this.queueService = queueService;
        this.clientResolver = clientResolver;
        this.artifactService = artifactService;
        this.generatedImageCodec = generatedImageCodec;
        this.imageConsumer = imageConsumer;
        this.globalAssetTaskPool = globalAssetTaskPool;
        this.leaseMs = leaseMs;
        this.clock = clock;
    }

    /**
     * Requests the resource's image, queueing a generation only if it has none and none is running.
     *
     * @throws NoSuchElementException (404) when the resource doesn't exist
     * @throws GlobalAssetException (422) when the resource isn't eligible (no name)
     */
    public GlobalAssetImageResponseDTO request(GlobalResourceType type, Long resourceId, Long requestedBy) {
        return requestFor(requireResource(type, resourceId), requestedBy);
    }

    /** Requests images for the given resources; unknown or ineligible ones are reported per item. */
    public GlobalAssetBatchResponseDTO requestBatch(GlobalResourceType type, List<Long> resourceIds, Long requestedBy) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            throw GlobalAssetException.badRequest("resourceIds must contain at least one id");
        }
        if (resourceIds.stream().anyMatch(Objects::isNull)) {
            throw GlobalAssetException.badRequest("resourceIds must not contain null");
        }
        List<Long> distinctIds = new ArrayList<>(new LinkedHashSet<>(resourceIds));
        if (distinctIds.size() > MAX_BATCH_IDS) {
            throw GlobalAssetException.badRequest("At most " + MAX_BATCH_IDS + " resourceIds per request");
        }
        List<GlobalAssetImageResponseDTO> items = distinctIds.stream()
                .map(id -> resourceResolver.find(type, id)
                        .map(resource -> requestReportingIneligible(resource, requestedBy))
                        .orElseGet(() -> notFound(type, id)))
                .toList();
        return logBatch(GlobalAssetBatchResponseDTO.of(type, items), requestedBy, "ids");
    }

    /** Requests images for every resource of {@code type} that has none yet. */
    public GlobalAssetBatchResponseDTO requestMissing(GlobalResourceType type, Long requestedBy) {
        List<GlobalAssetImageResponseDTO> items = resourceResolver.findMissingImages(type).stream()
                .map(resource -> requestReportingIneligible(resource, requestedBy))
                .toList();
        return logBatch(GlobalAssetBatchResponseDTO.of(type, items), requestedBy, "missing");
    }

    /** The resource's current image state, for polling a queued generation. */
    public GlobalAssetImageResponseDTO status(GlobalResourceType type, Long resourceId) {
        GlobalResource resource = requireResource(type, resourceId);
        VisualizationAsset asset = assetStore.find(type.assetKey(resourceId)).orElse(null);
        String message = StringUtils.hasText(resource.imageUrl()) ? "Image available"
                : asset == null ? "No image generated yet"
                : "Image generation " + asset.getGenerationStatus();
        return response(resource, asset, null, message);
    }

    private GlobalAssetImageResponseDTO requestFor(GlobalResource resource, Long requestedBy) {
        if (!StringUtils.hasText(resource.name())) {
            throw GlobalAssetException.notEligible(
                    resource.type().pathSegment() + " " + resource.id() + " has no name to generate an image from");
        }
        String assetKey = resource.type().assetKey(resource.id());
        Optional<VisualizationAsset> existing = assetStore.find(assetKey);

        if (StringUtils.hasText(resource.imageUrl())) {
            return response(resource, existing.orElse(null), Outcome.ALREADY_EXISTS, "Image already exists");
        }
        if (existing.isPresent() && isReady(existing.get())) {
            // The asset finished but the catalog never got the URL (e.g. it was cleared): re-publish, don't regenerate.
            resourceResolver.publishImage(resource.type(), resource.id(), existing.get().getImageUrl());
            return response(resource, existing.get(), Outcome.ALREADY_EXISTS, "Image already exists");
        }

        Instant now = clock.instant();
        String token = UUID.randomUUID().toString();
        Optional<VisualizationAsset> claimed = assetStore.claim(resource, token, requestedBy, now, now.plusMillis(leaseMs));
        if (claimed.isEmpty()) {
            VisualizationAsset current = assetStore.find(assetKey).orElse(null);
            log.debug("event=global_asset_image_joined assetKey={} requestedBy={} status={}",
                    assetKey, requestedBy, current == null ? null : current.getGenerationStatus());
            return current != null && isReady(current)
                    ? response(resource, current, Outcome.ALREADY_EXISTS, "Image already exists")
                    : response(resource, current, Outcome.IN_PROGRESS, "Image generation already in progress");
        }

        try {
            globalAssetTaskPool.submit(new NamedTask<>("global-asset:" + assetKey, () -> {
                generate(resource, assetKey, token, requestedBy);
                return null;
            }));
        } catch (RuntimeException e) {
            assetStore.markFailed(assetKey, token, "Could not queue generation: " + e.getClass().getSimpleName());
            throw e;
        }
        log.info("event=global_asset_image_requested requestedBy={} resourceType={} resourceId={} assetId={} assetKey={}",
                requestedBy, resource.type(), resource.id(), claimed.get().getId(), assetKey);
        return response(resource, claimed.get(), Outcome.QUEUED, "Image generation queued");
    }

    /**
     * Runs on the global-asset pool. Every failure is recorded on the asset (never thrown), and
     * only while this generation still owns the claim.
     */
    void generate(GlobalResource resource, String assetKey, String token, Long requestedBy) {
        OperationLog operation = OperationLog.start(log, "global_asset_image", assetKey,
                "requestedBy=" + requestedBy + " resourceType=" + resource.type() + " resourceId=" + resource.id());
        try {
            String prompt = promptBuilder.build(resource);
            if (!assetStore.markGenerating(assetKey, token, prompt)) {
                operation.completed("outcome=superseded");
                return;
            }

            AiArtifactSpec<GeneratedImage> artifactSpec = AiArtifactSpec.of(
                    AiArtifactKeyBuilder.build(AiCapability.TEXT_TO_IMAGE, CORRELATION_TYPE, assetKey, prompt),
                    generatedImageCodec,
                    GlobalAssetImageArtifactConsumer.CONSUMER_ID);

            AiRequestOutcome<GeneratedImage> outcome = queueService.executeInline(
                    requestedBy,
                    AiCapability.TEXT_TO_IMAGE,
                    null,
                    null,
                    CORRELATION_TYPE,
                    assetKey,
                    artifactSpec,
                    selection -> clientResolver.resolveImageClient(selection.model()).generate(prompt));

            // The payload is durable from here on, whether it was just generated or reused.
            AiArtifact artifact = artifactOrTransient(outcome, assetKey);
            imageConsumer.consume(artifact, outcome.value());
            artifactService.markConsumed(artifact.getId());

            ModelSelectionOutcome selection = outcome.selection();
            operation.completed("outcome=ready modelKey=" + selection.model().getKey()
                    + " tier=" + selection.model().getTier()
                    + " usedFallback=" + selection.usedFallback()
                    + " reused=" + outcome.reused());
        } catch (Exception e) {
            assetStore.markFailed(assetKey, token, describeFailure(e));
            // Any generated payload is retained in the artifact store; a retry or the recovery job reuses it.
            operation.failed(e, "outcome=failed payloadRetained=true");
        } finally {
            operation.close();
        }
    }

    private GlobalAssetImageResponseDTO requestReportingIneligible(GlobalResource resource, Long requestedBy) {
        try {
            return requestFor(resource, requestedBy);
        } catch (GlobalAssetException e) {
            return GlobalAssetImageResponseDTO.builder()
                    .resourceType(resource.type())
                    .resourceId(resource.id())
                    .resourceName(resource.name())
                    .outcome(Outcome.NOT_ELIGIBLE)
                    .message(e.getMessage())
                    .build();
        }
    }

    private GlobalResource requireResource(GlobalResourceType type, Long resourceId) {
        return resourceResolver.find(type, resourceId)
                .orElseThrow(() -> new NoSuchElementException(
                        "No " + type.pathSegment() + " resource found with id " + resourceId));
    }

    private static boolean isReady(VisualizationAsset asset) {
        return asset.getGenerationStatus() == GlobalAssetStatus.READY && StringUtils.hasText(asset.getImageUrl());
    }

    private static GlobalAssetImageResponseDTO notFound(GlobalResourceType type, Long resourceId) {
        return GlobalAssetImageResponseDTO.builder()
                .resourceType(type)
                .resourceId(resourceId)
                .outcome(Outcome.NOT_FOUND)
                .message("No " + type.pathSegment() + " resource found with id " + resourceId)
                .build();
    }

    private static GlobalAssetImageResponseDTO response(
            GlobalResource resource, VisualizationAsset asset, Outcome outcome, String message) {
        String imageUrl = StringUtils.hasText(resource.imageUrl()) ? resource.imageUrl()
                : asset != null ? asset.getImageUrl() : null;
        return GlobalAssetImageResponseDTO.builder()
                .resourceType(resource.type())
                .resourceId(resource.id())
                .resourceName(resource.name())
                .assetId(asset == null ? null : asset.getId())
                .status(asset == null ? null : asset.getGenerationStatus())
                .outcome(outcome)
                .imageUrl(imageUrl)
                .failureReason(asset == null ? null : asset.getImageFailureReason())
                .message(message)
                .build();
    }

    private GlobalAssetBatchResponseDTO logBatch(GlobalAssetBatchResponseDTO batch, Long requestedBy, String mode) {
        log.info("event=global_asset_image_batch_requested requestedBy={} resourceType={} mode={} requested={} queued={}"
                        + " inProgress={} alreadyExists={} notFound={} notEligible={}",
                requestedBy, batch.getResourceType(), mode, batch.getRequested(), batch.getQueued(),
                batch.getInProgress(), batch.getAlreadyExists(), batch.getNotFound(), batch.getNotEligible());
        return batch;
    }

    private static AiArtifact artifactOrTransient(AiRequestOutcome<GeneratedImage> outcome, String assetKey) {
        if (outcome.artifact() != null) {
            return outcome.artifact();
        }
        ModelSelectionOutcome selection = outcome.selection();
        AiArtifact transientArtifact = new AiArtifact();
        transientArtifact.setCorrelationId(assetKey);
        transientArtifact.setCorrelationType(CORRELATION_TYPE);
        transientArtifact.setCapability(AiCapability.TEXT_TO_IMAGE);
        transientArtifact.setProducedByModelKey(selection.model().getKey());
        transientArtifact.setProducedByTier(selection.model().getTier());
        transientArtifact.setUsedFallback(selection.usedFallback());
        return transientArtifact;
    }

    private static String describeFailure(Exception e) {
        String message = e.getMessage();
        return StringUtils.hasText(message)
                ? e.getClass().getSimpleName() + ": " + message
                : e.getClass().getSimpleName();
    }
}
