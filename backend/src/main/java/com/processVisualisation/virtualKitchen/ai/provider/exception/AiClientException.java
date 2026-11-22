package com.processVisualisation.virtualKitchen.ai.provider.exception;

import com.processVisualisation.virtualKitchen.common.web.ApiException;
import com.processVisualisation.virtualKitchen.common.web.ErrorCode;

import org.springframework.http.HttpStatus;

/**
 * Base unchecked exception for all failures raised by the AI provider and
 * storage clients in this package. Provider-specific failure conditions
 * (communication errors, authentication failures, timeouts, invalid
 * responses) are represented by the dedicated subclasses in this package
 * rather than being thrown directly as this type.
 */
public class AiClientException extends ApiException {

    /**
     * Creates the exception with a descriptive message and no cause.
     *
     * @param message a message describing the failure
     */
    public AiClientException(String message) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR, "AI Client Error", Outcome.UPSTREAM_FAILURE);
    }

    /**
     * Creates the exception with a descriptive message and the underlying
     * cause.
     *
     * @param message a message describing the failure
     * @param cause the underlying exception that caused this failure
     */
    public AiClientException(String message, Throwable cause) {
        super(message, cause, HttpStatus.INTERNAL_SERVER_ERROR, "AI Client Error", Outcome.UPSTREAM_FAILURE);
    }

    /** For subclasses: a provider failure answered with its own status and error title. */
    protected AiClientException(String message, Throwable cause, HttpStatus status, String error) {
        super(message, cause, status, error, Outcome.UPSTREAM_FAILURE);
    }

    /** Message sent to users for every provider failure; the provider's own error text is only logged. */
    public static final String CLIENT_MESSAGE =
            "The AI service had a problem handling this request. Please try again in a moment.";

    @Override
    public ErrorCode getCode() {
        return ErrorCode.AI_UNAVAILABLE;
    }

    @Override
    public String getClientMessage() {
        return CLIENT_MESSAGE;
    }
}
