package com.processVisualisation.virtualKitchen.common.web;

import org.springframework.http.HttpStatus;

/**
 * Base class for the application's own exceptions that end a request with a specific HTTP response. Each one
 * carries the status and short error title {@link GlobalExceptionHandler} answers with, and the {@link Outcome}
 * that decides how the failure is logged, so neither the handler nor
 * {@link com.processVisualisation.virtualKitchen.common.logging.FailureLogger} needs to know individual feature
 * exceptions.
 */
public abstract class ApiException extends RuntimeException {

    /** What kind of failure this is, which sets the level it is logged at where it is finally handled. */
    public enum Outcome {
        /** The caller's mistake (bad input, unknown resource): INFO for a request, WARN for a background job. */
        CLIENT_ERROR,
        /** An expected but unwanted outcome (forbidden, conflict, invalid AI output, capacity): WARN, no stack. */
        REJECTED,
        /** A dependency (AI provider, model routing) failed: ERROR without a stack, the message says what. */
        UPSTREAM_FAILURE
    }

    private final HttpStatus status;
    private final String error;
    private final Outcome outcome;

    /**
     * @param error the short title for the response body's {@code error} field, or null for the status' reason phrase
     */
    protected ApiException(String message, HttpStatus status, String error, Outcome outcome) {
        super(message);
        this.status = status;
        this.error = error;
        this.outcome = outcome;
    }

    /**
     * @param error the short title for the response body's {@code error} field, or null for the status' reason phrase
     */
    protected ApiException(String message, Throwable cause, HttpStatus status, String error, Outcome outcome) {
        super(message, cause);
        this.status = status;
        this.error = error;
        this.outcome = outcome;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** The response body's {@code error} title: the one this exception names, else the status' reason phrase. */
    public String getError() {
        return error != null ? error : status.getReasonPhrase();
    }

    public Outcome getOutcome() {
        return outcome;
    }

    /** The response's machine-readable code; by default the one for its status. */
    public ErrorCode getCode() {
        return ErrorCode.forStatus(status);
    }

    /**
     * The message sent to the client. By default the exception's own message, which feature code writes for
     * users; exceptions whose message can carry internal or third-party detail override this.
     */
    public String getClientMessage() {
        return getMessage();
    }
}
