package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAsset;
import com.processVisualisation.virtualKitchen.ai.provider.ImageStorageClient;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The scene anchor switch can be changed at runtime (admin override) and takes effect on the next call. */
class VisualizationSceneAnchorServiceToggleTest {

    private final RecipeProcessVisualizationService visualizationService = mock(RecipeProcessVisualizationService.class);
    private final ImageStorageClient storage = mock(ImageStorageClient.class);

    @Test
    void switchedOffAtRuntime_returnsNoAnchor_andTouchesNothing() {
        VisualizationSceneAnchorService service = new VisualizationSceneAnchorService(visualizationService, storage, true);

        service.setEnabled(false);

        assertTrue(service.ensureAnchor(1L, 100L).isEmpty());
        assertFalse(service.isEnabled());
        assertTrue(service.isBaselineEnabled(), "the configured value is kept for reset");
        verify(visualizationService, never()).findScene(any());
        verify(visualizationService, never()).generateSceneImage(any(), any());
    }

    @Test
    void switchedOnAtRuntime_usesTheRecipesScene() {
        VisualizationSceneAnchorService service = new VisualizationSceneAnchorService(visualizationService, storage, false);
        VisualizationAsset scene = new VisualizationAsset();
        scene.setId(7L);
        scene.setImagePrompt("empty kadai on granite");
        scene.setDishContext("Part of making Egg Curry");
        scene.setImageUrl("https://storage/scene.png");
        when(visualizationService.findScene(100L)).thenReturn(Optional.of(scene));
        when(storage.download("https://storage/scene.png"))
                .thenReturn(new ImageStorageClient.StoredImage("image/png", new byte[]{1, 2, 3}));

        assertTrue(service.ensureAnchor(1L, 100L).isEmpty(), "off as configured");

        service.setEnabled(true);

        Optional<RecipeProcessVisualizationService.SceneReference> anchor = service.ensureAnchor(1L, 100L);
        assertTrue(anchor.isPresent());
        assertTrue(anchor.get().image() != null);
    }
}
