package com.processVisualisation.virtualKitchen.common.web;

/**
 * Maximum sizes of user-supplied text and lists in request bodies, used by the DTOs' {@code @Size} constraints.
 * They keep one request from carrying unbounded text to the database or to a paid AI call. Annotation values must
 * be compile-time constants, so these are code, not configuration. The frontend mirrors the user-facing ones
 * ({@code frontEnd/src/shared/inputLimits.ts}) with equal or smaller values; keep the two in step.
 * The whole request body is capped separately by {@link RequestSizeLimitFilter}.
 */
public final class InputLimits {

    private InputLimits() {
    }

    /** Free recipe text sent to AI recipe generation, editing or refinement. */
    public static final int RECIPE_TEXT = 8000;
    /** A prompt to the admin AI chat. */
    public static final int CHAT_PROMPT = 4000;
    /** An image prompt carried by an asset import. */
    public static final int IMAGE_PROMPT = 4000;
    /** Names: recipes, processes, ingredients, equipment, custom actions. */
    public static final int NAME = 200;
    /** Descriptions of recipes, processes, ingredients and equipment. */
    public static final int DESCRIPTION = 2000;
    /** A step title. */
    public static final int STEP_TITLE = 200;
    /** A step's longer text fields (description, expected result, summary). */
    public static final int STEP_TEXT = 4000;
    /** Short notes, such as an ingredient's notes or preparation. */
    public static final int NOTE = 1000;
    /** Identifiers sent as strings (node ids). */
    public static final int ID = 200;
    /** A URL. */
    public static final int URL = 2048;

    /** Nodes in one saved process. */
    public static final int PROCESS_NODES = 500;
    /** Edges in one saved process. */
    public static final int PROCESS_EDGES = 1000;
    /** Processes saved in one batch. */
    public static final int PROCESS_BATCH = 50;
    /** Ingredients on one recipe. */
    public static final int RECIPE_INGREDIENTS = 200;
    /** Steps named in one narration batch. */
    public static final int NARRATION_STEPS = 200;
}
