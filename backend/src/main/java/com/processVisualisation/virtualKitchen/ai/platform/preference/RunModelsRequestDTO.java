package com.processVisualisation.virtualKitchen.ai.platform.preference;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * The models the user picked for one AI action (capability name to model key, e.g. {@code TEXT_TO_IMAGE ->
 * "gpt-image-1"}). Blank or missing entries keep the user's account setting. Checked with
 * {@link PreferredModelResolver#validateRunChoices} and applied with {@link AiRunModels#open}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RunModelsRequestDTO {

    private Map<String, String> modelKeys;
}
