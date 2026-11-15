package com.processVisualisation.virtualKitchen.ai.recipe.visualization.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Optional body of "Generate visuals": the models picked for the run (capability name to model key; blank or missing
 * = the account setting), and what to do with steps that already have an image: keep them (default) or regenerate
 * and replace them.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VisualizationJobRequestDTO {

    private Map<String, String> modelKeys;

    private boolean overwriteExisting;
}
