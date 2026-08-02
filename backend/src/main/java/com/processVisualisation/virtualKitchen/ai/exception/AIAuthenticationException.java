package com.processVisualisation.virtualKitchen.ai.exception;

public class AIAuthenticationException extends AIClientException {

    public AIAuthenticationException(String message) {
        super(message);
    }

    public AIAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
