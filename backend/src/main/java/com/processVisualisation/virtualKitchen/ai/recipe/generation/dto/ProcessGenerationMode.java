package com.processVisualisation.virtualKitchen.ai.recipe.generation.dto;

/**
 * What an AI process-generation request asks for. Chosen explicitly by the client (it knows whether a flow is open
 * and what is selected) rather than guessed by the model: a wrongly guessed CREATE would discard the user's flow.
 */
public enum ProcessGenerationMode {
    /** Convert recipe text into a complete new MAIN process (+ subprocesses), replacing the current MAIN. */
    CREATE,
    /** Apply a natural-language change to one existing process as a minimal list of edit operations. */
    EDIT,
    /**
     * Re-author the whole saved recipe (every process) as a corrected complete flow: fixed ingredient tagging, step
     * summaries and titles, and the Preparation/main split. The cook reviews it before it replaces their flow.
     */
    REFINE
}
