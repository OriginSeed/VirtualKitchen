package com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto;

import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the user ticked in "What would you like AI to create?". {@code completeExperience} is the
 * convenience option and selects every task; the explicit {@code tasks} list may repeat entries or
 * overlap with it — the selection is normalised to a distinct set either way.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecipeAiTaskSelectionDTO {

    private List<RecipeAiTaskType> tasks = new ArrayList<>();

    private boolean completeExperience;

    /**
     * Models for this run only, by capability name ({@code TEXT_TO_TEXT}, {@code TEXT_TO_IMAGE}); a missing entry
     * uses the user's saved choice, then the default. Priced in the estimate and charged when the run's requests go.
     */
    private Map<String, String> modelKeys = new LinkedHashMap<>();

    public RecipeAiTaskSelectionDTO(List<RecipeAiTaskType> tasks, boolean completeExperience) {
        this(tasks, completeExperience, new LinkedHashMap<>());
    }
}
