package com.processVisualisation.virtualKitchen.ai.asset;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data MongoDB repository for {@link VisualizationAsset}, persisting
 * generated visualization assets (images/videos) in the
 * {@code visualization_asset} collection.
 */
public interface VisualizationAssetRepository extends MongoRepository<VisualizationAsset, Long> {
    /** Finds the visualization asset previously generated for the given cache/dedup key, if any. */
    Optional<VisualizationAsset> findByVisualizationKey(String visualizationKey);

    /** The assets stored under any of {@code visualizationKeys} (missing keys are simply absent). */
    List<VisualizationAsset> findByVisualizationKeyIn(Collection<String> visualizationKeys);

    /** Every asset whose key starts with {@code prefix}, e.g. a recipe's shared panel images ({@code recipeId::grid::}). */
    List<VisualizationAsset> findByVisualizationKeyStartingWith(String prefix);
}
