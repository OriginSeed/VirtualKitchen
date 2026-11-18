package com.processVisualisation.virtualKitchen.ai.recipe.generation.dto;

import com.processVisualisation.virtualKitchen.ai.recipe.edit.dto.RecipeProcessEditResultDTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of AI-driven Process generation: a semantic MAIN process plus zero
 * or more SUBPROCESSes (see {@link GeneratedRecipeProcessDTO}), not yet persisted
 * — the frontend loads this straight into the current Recipe working
 * session (RecipeSessionContext) for the user to review/edit, and nothing is
 * written to the database until they explicitly Save.
 * <p>
 * An EDIT job carries its operations in {@link #edit} instead, with mainProcess/subprocesses left null.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecipeProcessGenerationResultDTO {

    private GeneratedRecipeProcessDTO mainProcess;
    private List<GeneratedRecipeProcessDTO> subprocesses;

    /** Which kind of request produced this result; null on results stored before modes existed (= CREATE). */
    private ProcessGenerationMode mode;
    private RecipeProcessEditResultDTO edit;
    /** What the application corrected in the model's answer (e.g. an uncatalogued ingredient kept as a custom one). */
    private List<String> repairs;
    /** Accepted but worth the user's attention (e.g. a preparation whose output the main process never uses). */
    private List<String> warnings;
    /** REFINE only: what was corrected in the cook's flow, one short line each. */
    private List<String> changes;

    private String modelUsed;
    private String modelTier;
    private boolean usedFallback;
    private String fallbackReason;
}
