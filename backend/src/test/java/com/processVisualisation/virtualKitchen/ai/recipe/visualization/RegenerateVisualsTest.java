package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAsset;
import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAssetRepository;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService.PrepFlow;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService.VisualTarget;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** "Regenerate and replace": what {@link RecipeProcessVisualizationService#clearForRegeneration} forgets. */
class RegenerateVisualsTest {

    private final VisualizationAssetRepository assets = mock(VisualizationAssetRepository.class);
    private final RecipeProcessVisualizationService service = new RecipeProcessVisualizationService(
            null, assets, null, null, null, null, null, null, null, null, null);

    @Test
    @SuppressWarnings("unchecked")
    void clearsEveryTargetsPromptAndImage_andThePanelsTheyWereCutFrom_butKeepsTheScene() {
        VisualizationAsset main = asset("9::s1", "https://img/s1.png", "fry onions");
        main.setReusesPrevious(true);
        VisualizationAsset prep = asset("9::p1", "https://img/p1.png", "chop");
        prep.setSourceGridKey("9::grid::p1+p2");
        VisualizationAsset grid = asset("9::grid::p1+p2", "https://img/grid.png", "panels");
        VisualizationAsset otherGrid = asset("9::grid::x1+x2", "https://img/other.png", "other panels");
        when(assets.findByVisualizationKeyIn(any())).thenReturn(List.of(main, prep));
        when(assets.findByVisualizationKeyStartingWith(anyString())).thenReturn(List.of(grid, otherGrid));

        int cleared = service.clearForRegeneration(9L, List.of(
                new VisualTarget("s1", null, null),
                new VisualTarget("p1", null, null, new PrepFlow("p2", "Chopped onions"))));

        ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(Collection.class);
        verify(assets).findByVisualizationKeyIn(keys.capture());
        assertEquals(List.of("9::s1", "9::p1"), List.copyOf(keys.getValue()));
        verify(assets).findByVisualizationKeyStartingWith("9::grid::");

        assertEquals(3, cleared);
        for (VisualizationAsset asset : List.of(main, prep, grid)) {
            assertNull(asset.getImageUrl(), asset.getVisualizationKey());
            assertNull(asset.getImagePrompt(), asset.getVisualizationKey());
        }
        assertNull(main.getReusesPrevious(), "look-alike steps are worked out again");
        assertNull(prep.getSourceGridKey());
        assertEquals("https://img/other.png", otherGrid.getImageUrl(), "panels of other steps are untouched");
        verify(assets).saveAll(List.of(main, prep, grid));
    }

    @Test
    void nothingStored_clearsNothing() {
        when(assets.findByVisualizationKeyIn(any())).thenReturn(List.of());
        when(assets.findByVisualizationKeyStartingWith(anyString())).thenReturn(List.of());

        assertEquals(0, service.clearForRegeneration(9L, List.of(new VisualTarget("s1", null, null))));
        assertTrue(true);
    }

    private static VisualizationAsset asset(String key, String imageUrl, String prompt) {
        VisualizationAsset asset = new VisualizationAsset();
        asset.setVisualizationKey(key);
        asset.setImageUrl(imageUrl);
        asset.setImagePrompt(prompt);
        return asset;
    }
}
