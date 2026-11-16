package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAsset;
import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAssetRepository;
import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAssetType;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelTier;
import com.processVisualisation.virtualKitchen.ai.provider.ImageStorageClient;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.RecipeStoryboard;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationGridSlicer;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationKeyBuilder;
import com.processVisualisation.virtualKitchen.common.persistence.SequenceGeneratorService;
import com.processVisualisation.virtualKitchen.recipe.repository.ProcessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 4 of a storyboard, without any AI call: cuts the combined image into its panels, stores each panel image, and
 * puts it on every step the panel stands for, through the same asset and node fields step visuals use
 * ({@code recipeId::stepId} asset, {@link ProcessRepository#setStepVisualization}). Steps of one panel share its image.
 */
@Component
public class StoryboardPanelAttacher {

    private static final Logger log = LoggerFactory.getLogger(StoryboardPanelAttacher.class);

    /** {@link VisualizationAsset#getSourceGridKey()} of a step image cut from a storyboard. */
    static final String SOURCE_PREFIX = "storyboard:";

    private final ImageStorageClient imageStorageClient;
    private final VisualizationAssetRepository assetRepository;
    private final SequenceGeneratorService sequenceGeneratorService;
    private final ProcessRepository processRepository;

    public StoryboardPanelAttacher(ImageStorageClient imageStorageClient, VisualizationAssetRepository assetRepository,
                                   SequenceGeneratorService sequenceGeneratorService, ProcessRepository processRepository) {
        this.imageStorageClient = imageStorageClient;
        this.assetRepository = assetRepository;
        this.sequenceGeneratorService = sequenceGeneratorService;
        this.processRepository = processRepository;
    }

    /**
     * Cuts {@code image} into the storyboard's grid and stores one image per panel, setting each panel's
     * {@code imageUrl} and {@code crop}.
     *
     * @return the size of the combined image as {@code [width, height]}
     * @throws IOException when the image cannot be read or cut
     */
    public int[] cutPanels(RecipeStoryboard storyboard, byte[] image) throws IOException {
        RecipeStoryboard.Layout layout = storyboard.getLayout();
        List<VisualizationGridSlicer.Tile> tiles = VisualizationGridSlicer.sliceGrid(image, layout.getRows(), layout.getColumns());
        int width = 0;
        int height = 0;
        for (VisualizationGridSlicer.Tile tile : tiles) {
            width = Math.max(width, tile.x() + tile.width());
            height = Math.max(height, tile.y() + tile.height());
        }
        for (RecipeStoryboard.Panel panel : storyboard.getPanels()) {
            int index = panel.getSequence() - 1;
            if (index < 0 || index >= tiles.size()) continue;
            VisualizationGridSlicer.Tile tile = tiles.get(index);
            String path = String.format("storyboards/%s/%s/panels/%02d.png", storyboard.getRecipeId(), storyboard.getId(),
                    panel.getSequence());
            panel.setImageUrl(imageStorageClient.upload(tile.png(), "image/png", path));
            RecipeStoryboard.Crop crop = new RecipeStoryboard.Crop();
            crop.setX(tile.x());
            crop.setY(tile.y());
            crop.setWidth(tile.width());
            crop.setHeight(tile.height());
            panel.setCrop(crop);
        }
        log.debug("event=storyboard_panels_cut storyboardId={} panels={} rows={} columns={}", storyboard.getId(),
                storyboard.getPanels().size(), layout.getRows(), layout.getColumns());
        return new int[]{width, height};
    }

    /**
     * Puts each panel's image on the steps it stands for. A step that is in several panels takes the first; a step
     * that already has an image keeps it unless {@code overwriteExisting}. Checks and steps unknown to {@code source}
     * (deleted meanwhile) are skipped.
     *
     * @return one result per covered step, in panel order
     */
    public List<RecipeStoryboard.StepResult> attachToSteps(RecipeStoryboard storyboard, StoryboardSource source,
                                                           boolean overwriteExisting) {
        Map<String, StoryboardSource.Node> byNodeId = new HashMap<>();
        source.nodes().values().forEach(node -> byNodeId.put(node.nodeId(), node));
        List<RecipeStoryboard.StepResult> results = new ArrayList<>();
        Set<String> done = new HashSet<>();
        String recipeId = String.valueOf(storyboard.getRecipeId());
        for (RecipeStoryboard.Panel panel : storyboard.getPanels()) {
            if (!StringUtils.hasText(panel.getImageUrl())) continue;
            for (String stepId : panel.getSourceStepIds()) {
                StoryboardSource.Node node = byNodeId.get(stepId);
                if (node == null || !node.step() || !done.add(stepId)) continue;

                String key = VisualizationKeyBuilder.build(recipeId, stepId);
                VisualizationAsset asset = assetRepository.findByVisualizationKey(key).orElse(null);
                boolean hasImage = node.hasImage() || (asset != null && StringUtils.hasText(asset.getImageUrl()));
                RecipeStoryboard.StepResult result = new RecipeStoryboard.StepResult();
                result.setProcessId(node.processId());
                result.setStepId(stepId);
                result.setPanelId(panel.getPanelId());
                if (hasImage && !overwriteExisting) {
                    result.setAttached(false);
                    results.add(result);
                    continue;
                }
                if (asset == null) {
                    asset = new VisualizationAsset();
                    asset.setId(sequenceGeneratorService.generateSequence(VisualizationAsset.SEQUENCE_NAME));
                    asset.setVisualizationKey(key);
                    asset.setType(VisualizationAssetType.ATOMIC);
                }
                // The panel's prompt is self-contained, so the asset carries no focus (see VisualizationAsset#focus).
                asset.setImagePrompt(panel.getImagePrompt());
                asset.setFocus(null);
                asset.setReusesPrevious(null);
                asset.setImageUrl(panel.getImageUrl());
                asset.setImageFailureReason(null);
                asset.setSourceGridKey(SOURCE_PREFIX + storyboard.getId());
                asset.setResolvedModelKey(storyboard.getImageModelKey());
                asset.setResolvedTier(tierOf(storyboard.getImageModelTier()));
                asset.setUsedFallback(storyboard.isUsedFallback());
                asset.setVideoPrompt(null);
                asset.setVideoUrl(null);
                asset = assetRepository.save(asset);
                processRepository.setStepVisualization(node.processId(), stepId, asset.getId(), asset.getImagePrompt(),
                        asset.getImageUrl());

                result.setVisualizationAssetId(asset.getId());
                result.setImageUrl(asset.getImageUrl());
                result.setImagePrompt(asset.getImagePrompt());
                result.setAttached(true);
                results.add(result);
            }
        }
        return results;
    }

    private static ModelTier tierOf(String name) {
        if (!StringUtils.hasText(name)) return null;
        try {
            return ModelTier.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
