package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAsset;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient;
import com.processVisualisation.virtualKitchen.ai.provider.ImageStorageClient;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService.SceneReference;
import com.processVisualisation.virtualKitchen.common.logging.FailureLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides a recipe's scene anchor to its step images: one image of the empty setting (countertop, the main pan,
 * prep bowls, lighting, camera angle, no food) that every step image is drawn against, plus a one-line dish context.
 * Its prompt and dish context are written by {@link VisualizationPromptBatchService}; this service generates the image
 * once (stored like any step image, under {@code recipeId::scene}) and reads it back for later jobs.
 * <p>
 * Best effort, like the prompt batch: when the anchor can't be made or read, steps get the dish context alone, or
 * nothing, and are generated from their prompt exactly as before. Calls for one recipe run one at a time, so the
 * visuals jobs started together for a recipe generate one anchor between them.
 */
@Service
public class VisualizationSceneAnchorService {

    private static final Logger log = LoggerFactory.getLogger(VisualizationSceneAnchorService.class);

    private final RecipeProcessVisualizationService visualizationService;
    private final ImageStorageClient imageStorageClient;
    private final boolean enabled;

    private final Map<Long, Object> recipeLocks = new ConcurrentHashMap<>();

    public VisualizationSceneAnchorService(
            RecipeProcessVisualizationService visualizationService,
            ImageStorageClient imageStorageClient,
            @Value("${ai.visualization.scene-anchor.enabled:true}") boolean enabled
    ) {
        this.visualizationService = visualizationService;
        this.imageStorageClient = imageStorageClient;
        this.enabled = enabled;
    }

    /** False turns scene anchors off entirely: no scene prompt is asked for and step images use their prompt alone. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * The scene anchor step images of this recipe should be drawn against, generating its image first when the
     * scene has a prompt but no image yet. Never throws.
     *
     * @return the anchor (with its image when one could be made or read), or empty when the recipe has no scene
     */
    public Optional<SceneReference> ensureAnchor(Long userId, Long recipeId) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            synchronized (recipeLocks.computeIfAbsent(recipeId, id -> new Object())) {
                return resolve(userId, recipeId);
            }
        } catch (Exception e) {
            FailureLogger.logFailure(log, "visualization_scene_anchor_failed", e,
                    "recipeId=" + recipeId + " fallback=prompt_only");
            return Optional.empty();
        }
    }

    private Optional<SceneReference> resolve(Long userId, Long recipeId) {
        Optional<VisualizationAsset> found = visualizationService.findScene(recipeId);
        if (found.isEmpty() || !StringUtils.hasText(found.get().getImagePrompt())) {
            log.debug("event=visualization_scene_anchor_skipped recipeId={} reason=no_scene_prompt", recipeId);
            return Optional.empty();
        }
        VisualizationAsset scene = found.get();
        String dishContext = scene.getDishContext();

        if (!StringUtils.hasText(scene.getImageUrl())) {
            VisualizationAsset generated = visualizationService.generateSceneImage(userId, scene);
            if (generated == null) {
                log.warn("event=visualization_scene_anchor_unavailable recipeId={} reason=generation_failed fallback=dish_context_only", recipeId);
                return Optional.of(new SceneReference(null, dishContext, null));
            }
            scene = generated;
            log.info("event=visualization_scene_anchor_generated recipeId={} assetId={}", recipeId, scene.getId());
        }

        try {
            ImageStorageClient.StoredImage stored = imageStorageClient.download(scene.getImageUrl());
            ImageGenerationClient.ReferenceImage image = new ImageGenerationClient.ReferenceImage(stored.mimeType(), stored.data());
            return Optional.of(new SceneReference(scene.getId() + ":" + scene.getImageUrl(), dishContext, image));
        } catch (RuntimeException e) {
            FailureLogger.logFailure(log, "visualization_scene_anchor_unreadable", e,
                    "recipeId=" + recipeId + " fallback=dish_context_only");
            return Optional.of(new SceneReference(null, dishContext, null));
        }
    }
}
