package com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto;

import jakarta.validation.constraints.Size;
import com.processVisualisation.virtualKitchen.common.web.InputLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Starts AI Recipe Creation. {@code recipeText} is required only when the selection includes the
 * recipe process (it is what the process is generated from); visuals/narration alone work on the
 * recipe's existing process.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecipeAiWorkflowCreateRequestDTO {

    @Valid
    @NotNull(message = "selection is required")
    private RecipeAiTaskSelectionDTO selection;

    @Size(max = InputLimits.RECIPE_TEXT, message = "Recipe text can be at most " + InputLimits.RECIPE_TEXT + " characters")
    private String recipeText;

    /**
     * With visuals or narration on a recipe that already has steps (and no new process from text): first let AI refine
     * the cook's flow (tagging, summaries, titles, the Preparation/main split). The refined flow is reviewed and
     * approved like a generated one before the other tasks run. {@code recipeText}, if given, is a note for it.
     */
    private Boolean refine;

    /** A request without a refine choice (no refinement). */
    public RecipeAiWorkflowCreateRequestDTO(RecipeAiTaskSelectionDTO selection, String recipeText) {
        this(selection, recipeText, null);
    }
}
