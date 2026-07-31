package com.processVisualisation.virtualKitchen.ai.exception;

public class AICommunicationException extends AIClientException {

    public AICommunicationException(String message) {
        super(message);
    }

    public AICommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
