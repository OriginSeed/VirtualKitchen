package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import com.processVisualisation.virtualKitchen.ai.platform.analytics.AiAnalyticsRecorder;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifact;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.consumer.AiArtifactConsumer;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient.GeneratedImage;
import com.processVisualisation.virtualKitchen.ai.provider.ImageStorageClient;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.RecipeStoryboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Uploads a generated storyboard image to object storage and records its URL on the {@link RecipeStoryboard} it was
 * drawn for. Runs over the staged artifact, from the storyboard job on the first attempt and from
 * {@code AiArtifactRecoveryJob} when that attempt's upload failed, so a paid image is never lost to a storage outage.
 */
@Component
public class StoryboardImageArtifactConsumer implements AiArtifactConsumer {

    public static final String CONSUMER_ID = "storyboard-image-upload";

    private static final Logger log = LoggerFactory.getLogger(StoryboardImageArtifactConsumer.class);

    private final ImageStorageClient imageStorageClient;
    private final RecipeStoryboardRepository storyboardRepository;
    private final MongoTemplate mongoTemplate;

    private AiAnalyticsRecorder analytics = AiAnalyticsRecorder.disabled();

    public StoryboardImageArtifactConsumer(ImageStorageClient imageStorageClient,
                                           RecipeStoryboardRepository storyboardRepository, MongoTemplate mongoTemplate) {
        this.imageStorageClient = imageStorageClient;
        this.storyboardRepository = storyboardRepository;
        this.mongoTemplate = mongoTemplate;
    }

    /** Optional, so tests can construct this without analytics. */
    @Autowired(required = false)
    public void setAnalyticsRecorder(AiAnalyticsRecorder analytics) {
        this.analytics = analytics;
    }

    @Override
    public String consumerId() {
        return CONSUMER_ID;
    }

    /**
     * @param artifact the staged artifact; its {@code correlationId} is the storyboard's id
     * @param payload the decoded {@link GeneratedImage}
     * @return the public URL of the uploaded image
     */
    @Override
    public String consume(AiArtifact artifact, Object payload) {
        GeneratedImage image = (GeneratedImage) payload;
        String storyboardId = artifact.getCorrelationId();
        RecipeStoryboard storyboard = storyboardRepository.findById(storyboardId)
                .orElseThrow(() -> new IllegalStateException("No storyboard with id " + storyboardId));

        String path = String.format("storyboards/%s/%s/%s.png", storyboard.getRecipeId(), storyboardId, UUID.randomUUID());
        String imageUrl = imageStorageClient.upload(image.data(), image.mimeType(), path);

        mongoTemplate.updateFirst(
                query(where("_id").is(storyboardId)),
                new Update()
                        .set("imageUrl", imageUrl)
                        .set("imageModelKey", artifact.getProducedByModelKey())
                        .set("imageModelTier", artifact.getProducedByTier() == null ? null : artifact.getProducedByTier().name())
                        .set("usedFallback", artifact.isUsedFallback())
                        .set("updatedAt", Instant.now()),
                RecipeStoryboard.class);

        analytics.outputStored(artifact.getProducingJobId(), "IMAGE", "recipe_storyboard", storyboardId, imageUrl,
                image.mimeType(), (long) image.data().length);
        log.debug("event=image_upload_completed storyboardId={} artifactId={} bytes={}", storyboardId, artifact.getId(),
                image.data().length);
        return imageUrl;
    }
}
