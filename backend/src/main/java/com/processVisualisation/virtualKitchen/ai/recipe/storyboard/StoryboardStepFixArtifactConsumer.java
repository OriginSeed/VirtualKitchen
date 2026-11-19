package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifact;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.consumer.AiArtifactConsumer;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient.GeneratedImage;
import com.processVisualisation.virtualKitchen.ai.provider.ImageStorageClient;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.StoryboardStepFix;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Uploads a step image redrawn from the cook's feedback and records its URL on the {@link StoryboardStepFix}. Runs
 * over the staged artifact, from the fix itself and from {@code AiArtifactRecoveryJob} when that upload failed.
 */
@Component
public class StoryboardStepFixArtifactConsumer implements AiArtifactConsumer {

    public static final String CONSUMER_ID = "storyboard-step-fix-upload";

    private final ImageStorageClient imageStorageClient;
    private final StoryboardStepFixRepository fixRepository;
    private final MongoTemplate mongoTemplate;

    public StoryboardStepFixArtifactConsumer(ImageStorageClient imageStorageClient, StoryboardStepFixRepository fixRepository,
                                             MongoTemplate mongoTemplate) {
        this.imageStorageClient = imageStorageClient;
        this.fixRepository = fixRepository;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public String consumerId() {
        return CONSUMER_ID;
    }

    /** @param artifact its {@code correlationId} is the fix's id */
    @Override
    public String consume(AiArtifact artifact, Object payload) {
        GeneratedImage image = (GeneratedImage) payload;
        String fixId = artifact.getCorrelationId();
        StoryboardStepFix fix = fixRepository.findById(fixId)
                .orElseThrow(() -> new IllegalStateException("No step image fix with id " + fixId));
        String path = String.format("storyboards/%s/steps/%s/%s.png", fix.getRecipeId(), fix.getStepId(), fixId);
        String imageUrl = imageStorageClient.upload(image.data(), image.mimeType(), path);
        mongoTemplate.updateFirst(query(where("_id").is(fixId)),
                new Update().set("newImageUrl", imageUrl).set("imageModelKey", artifact.getProducedByModelKey())
                        .set("updatedAt", Instant.now()),
                StoryboardStepFix.class);
        return imageUrl;
    }
}
