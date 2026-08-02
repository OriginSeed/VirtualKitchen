package com.processVisualisation.virtualKitchen.exception;

public class RecipeFlowGenerationException extends RuntimeException {

    public RecipeFlowGenerationException(String message) {
        super(message);
    }

    public RecipeFlowGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
