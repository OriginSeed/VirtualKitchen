package com.processVisualisation.virtualKitchen.auth.service;

/**
 * An email could not be handed to the provider. Internal only: {@link EmailService} logs it and answers clients
 * with a generic message. The message never contains the API key, the recipient or the email content.
 */
class EmailDeliveryException extends RuntimeException {

    /** Why delivery failed, for the log line. */
    enum Reason {
        /** The API key or sender address is not set; no request was made. */
        NOT_CONFIGURED,
        /** The provider refused the request (4xx): bad key, unverified sender domain, invalid field. */
        REJECTED,
        /** The provider failed (5xx). */
        PROVIDER_ERROR,
        /** The provider could not be reached or did not answer in time. */
        NETWORK
    }

    private final Reason reason;
    /** HTTP status from the provider, or 0 when there was none. */
    private final int status;
    /** The provider's error name (e.g. {@code validation_error}), or null. */
    private final String providerError;

    EmailDeliveryException(Reason reason, int status, String providerError, Throwable cause) {
        super("Email delivery failed: " + reason + (status > 0 ? " status=" + status : "")
                + (providerError != null ? " error=" + providerError : ""), cause);
        this.reason = reason;
        this.status = status;
        this.providerError = providerError;
    }

    Reason getReason() {
        return reason;
    }

    int getStatus() {
        return status;
    }

    String getProviderError() {
        return providerError;
    }
}
