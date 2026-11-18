package com.processVisualisation.virtualKitchen.ai.recipe.generation.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One AI-generated STEP or CONDITION within a {@link GeneratedRecipeProcessDTO} —
 * semantic fields only (see {@code ProcessStepFields} on the frontend for
 * the equivalent shape once converted). No node id, position, dimensions, or
 * any other React Flow presentation field: the application generates those
 * when converting this into the Process working snapshot.
 * <p>
 * {@code action}/{@code actionOn} and the advanced cooking fields apply to a
 * STEP; {@code title}/{@code expectedResult} apply to a CONDITION. Both node
 * kinds carry {@code actionDescription} and {@code expectedOutput} — the core,
 * always-shown Step summary fields. Which advanced fields a STEP may carry is
 * decided per action by the shared catalog (see RecipeStepVocabularyProvider).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeneratedRecipeStepDTO {

    /** "STEP" or "CONDITION" — the only two node kinds a Process graph supports. */
    private String nodeType;

    /** Short slug unique within its process (e.g. "s1"), so a later step's actionOn.steps can reference this step's output. */
    private String stepId;

    // --- STEP-only fields ---
    private String action;
    /** Only meaningful when {@code action} is "custom". */
    private String customActionName;
    private GeneratedActionOnDTO actionOn;
    /** Numeric temperature; its meaning (oven/oil/liquid/...) comes from the action's catalog temperatureContext. */
    private Double temperatureValue;
    /** "C" or "F"; required whenever {@code temperatureValue} is set. */
    private String temperatureUnit;
    /** Legacy free-text temperature (e.g. "180 C") from before temperatureValue/temperatureUnit existed. */
    private String temperature;
    private String flameLevel;
    /** "&lt;number&gt; &lt;seconds|minutes|hours&gt;". */
    private String duration;
    /** How often to repeat the action during the step, same format as {@code duration} (e.g. "stir every 2 minutes"). */
    private String repeatInterval;

    // --- CONDITION: the question; STEP: an optional short title (empty = derived from the action) ---
    private String title;
    /** "success" or "failure". */
    private String expectedResult;

    // --- shared core fields (both STEP and CONDITION) ---
    private String actionDescription;
    private String expectedOutput;

    /** STEP only: a plain spoken explanation of the step that continues from the one before it (used by TTS). */
    private String summary;
}
