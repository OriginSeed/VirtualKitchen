package com.processVisualisation.virtualKitchen.common.web;

import org.springframework.http.HttpStatusCode;

/**
 * Stable, machine-readable reason sent as {@link ErrorResponse#getCode()} with every failed request. The frontend
 * picks the message it shows the user from this code (and the HTTP status), so the codes are a contract: add new
 * ones freely, but never rename or reuse one.
 */
public enum ErrorCode {
    /** The request body or a parameter failed validation; the message says what to fix. */
    VALIDATION_FAILED,
    /** The request was malformed or otherwise rejected as the caller's mistake. */
    BAD_REQUEST,
    /** Not signed in, or the session expired. */
    UNAUTHORIZED,
    /** Signed in, but not allowed to do this. */
    FORBIDDEN,
    NOT_FOUND,
    /** The resource changed or already exists. */
    CONFLICT,
    /** The request body was larger than the server accepts. */
    PAYLOAD_TOO_LARGE,
    UNSUPPORTED_MEDIA_TYPE,
    /** Too many requests from this user or address; retry after the {@code Retry-After} header. */
    RATE_LIMITED,
    /** The AI job queue is full; try again shortly. */
    AI_BUSY,
    /** An AI provider failed or timed out; the provider's own error text is logged, never sent. */
    AI_UNAVAILABLE,
    /** Anything unexpected on the server. */
    INTERNAL;

    /** The default code for a response with the given status, for exceptions that don't name their own. */
    public static ErrorCode forStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400, 422 -> VALIDATION_FAILED;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 409 -> CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> RATE_LIMITED;
            default -> status.is4xxClientError() ? BAD_REQUEST : INTERNAL;
        };
    }
}
