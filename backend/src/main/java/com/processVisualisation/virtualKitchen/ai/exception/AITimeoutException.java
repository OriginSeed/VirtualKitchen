package com.processVisualisation.virtualKitchen.ai.exception;

public class AITimeoutException extends AIClientException {

    public AITimeoutException(String message) {
        super(message);
    }

    public AITimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
