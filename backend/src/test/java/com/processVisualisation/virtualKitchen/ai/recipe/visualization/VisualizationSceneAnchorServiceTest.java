package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAsset;
import com.processVisualisation.virtualKitchen.ai.provider.ImageStorageClient;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService.SceneReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VisualizationSceneAnchorServiceTest {

    private static final long USER = 1L;
    private static final long RECIPE = 100L;
    private static final String URL = "https://storage.example/scene.png";

    private RecipeProcessVisualizationService visualizationService;
    private ImageStorageClient storage;
    private VisualizationSceneAnchorService service;

    @BeforeEach
    void setUp() {
        visualizationService = mock(RecipeProcessVisualizationService.class);
        storage = mock(ImageStorageClient.class);
        when(storage.download(URL)).thenReturn(new ImageStorageClient.StoredImage("image/png", new byte[]{7}));
        service = new VisualizationSceneAnchorService(visualizationService, storage, true);
    }

    @Test
    void sceneWithAnImage_isReadBackAndReturnedWithItsDishContext() {
        when(visualizationService.findScene(RECIPE)).thenReturn(Optional.of(scene(5L, "empty kadai", URL)));

        SceneReference anchor = service.ensureAnchor(USER, RECIPE).orElseThrow();

        assertEquals("Part of making Egg Curry", anchor.dishContext());
        assertEquals(7, anchor.image().data()[0]);
        assertEquals("5:" + URL, anchor.anchorKey());
        verify(visualizationService, never()).generateSceneImage(any(), any());
    }

    @Test
    void sceneWithoutAnImage_isGeneratedOnce() {
        VisualizationAsset prompted = scene(5L, "empty kadai", null);
        when(visualizationService.findScene(RECIPE)).thenReturn(Optional.of(prompted));
        when(visualizationService.generateSceneImage(USER, prompted)).thenReturn(scene(5L, "empty kadai", URL));

        SceneReference anchor = service.ensureAnchor(USER, RECIPE).orElseThrow();

        assertEquals(7, anchor.image().data()[0]);
        verify(visualizationService, times(1)).generateSceneImage(USER, prompted);
    }

    @Test
    void failedGeneration_leavesTheDishContextOnly() {
        VisualizationAsset prompted = scene(5L, "empty kadai", null);
        when(visualizationService.findScene(RECIPE)).thenReturn(Optional.of(prompted));
        when(visualizationService.generateSceneImage(USER, prompted)).thenReturn(null);

        SceneReference anchor = service.ensureAnchor(USER, RECIPE).orElseThrow();

        assertEquals("Part of making Egg Curry", anchor.dishContext());
        assertNull(anchor.image());
        assertNull(anchor.anchorKey());
    }

    @Test
    void unreadableImage_leavesTheDishContextOnly() {
        when(visualizationService.findScene(RECIPE)).thenReturn(Optional.of(scene(5L, "empty kadai", URL)));
        when(storage.download(URL)).thenThrow(new RuntimeException("storage down"));

        SceneReference anchor = service.ensureAnchor(USER, RECIPE).orElseThrow();

        assertNull(anchor.image());
        assertEquals("Part of making Egg Curry", anchor.dishContext());
    }

    @Test
    void noScenePrompt_meansNoAnchor() {
        when(visualizationService.findScene(RECIPE)).thenReturn(Optional.empty());

        assertTrue(service.ensureAnchor(USER, RECIPE).isEmpty());
        verifyNoInteractions(storage);
    }

    @Test
    void disabled_neverLooksForAScene() {
        service = new VisualizationSceneAnchorService(visualizationService, storage, false);

        assertTrue(service.ensureAnchor(USER, RECIPE).isEmpty());
        verifyNoInteractions(visualizationService, storage);
    }

    @Test
    void siblingJobs_generateOneAnchorBetweenThem() throws Exception {
        AtomicReference<VisualizationAsset> stored = new AtomicReference<>(scene(5L, "empty kadai", null));
        when(visualizationService.findScene(RECIPE)).thenAnswer(inv -> Optional.of(stored.get()));
        CountDownLatch generating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(visualizationService.generateSceneImage(eq(USER), any())).thenAnswer(inv -> {
            generating.countDown();
            release.await(5, TimeUnit.SECONDS);
            stored.set(scene(5L, "empty kadai", URL));
            return stored.get();
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> service.ensureAnchor(USER, RECIPE));
            assertTrue(generating.await(5, TimeUnit.SECONDS));
            var second = pool.submit(() -> service.ensureAnchor(USER, RECIPE));
            release.countDown();

            assertTrue(first.get(5, TimeUnit.SECONDS).orElseThrow().image() != null);
            assertTrue(second.get(5, TimeUnit.SECONDS).orElseThrow().image() != null);
        } finally {
            pool.shutdownNow();
        }
        verify(visualizationService, times(1)).generateSceneImage(eq(USER), any());
        verify(storage, times(2)).download(anyString());
    }

    private static VisualizationAsset scene(Long id, String prompt, String imageUrl) {
        VisualizationAsset scene = new VisualizationAsset();
        scene.setId(id);
        scene.setVisualizationKey(VisualizationKeyBuilder.buildScene(String.valueOf(RECIPE)));
        scene.setImagePrompt(prompt);
        scene.setDishContext("Part of making Egg Curry");
        scene.setImageUrl(imageUrl);
        return scene;
    }
}
