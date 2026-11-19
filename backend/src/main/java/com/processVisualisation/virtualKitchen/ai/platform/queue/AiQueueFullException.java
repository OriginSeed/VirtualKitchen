package com.processVisualisation.virtualKitchen.ai.platform.queue;

import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.common.web.ApiException;
import com.processVisualisation.virtualKitchen.common.web.ErrorCode;

import org.springframework.http.HttpStatus;

/**
 * Thrown when the bounded queue for a capability is already at its
 * configured max depth. Handled by {@code GlobalExceptionHandler} as HTTP
 * 429 — the caller should retry shortly rather than the request piling up
 * indefinitely behind an unbounded backlog.
 */
public class AiQueueFullException extends ApiException {

    public AiQueueFullException(AiCapability capability) {
        super("The AI system is busy handling " + capability + " requests — please try again in a moment",
                HttpStatus.TOO_MANY_REQUESTS, "AI System Busy", Outcome.REJECTED);
    }

    @Override
    public ErrorCode getCode() {
        return ErrorCode.AI_BUSY;
    }
}
