package com.processVisualisation.virtualKitchen.common.web;

import com.processVisualisation.virtualKitchen.common.logging.FailureLogger;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;

/**
 * Global exception handler for all REST controllers ({@code @RestControllerAdvice}).
 * Centralizes translation of both framework exceptions (Mongo duplicate keys,
 * bean-validation failures, rejected arguments) and this application's own
 * {@link ApiException}s into a consistent {@link ErrorResponse} JSON body with
 * an appropriate HTTP status. Feature exceptions carry their own status, so
 * this class needs no knowledge of individual features.
 * <p>
 * This is also the one place a failed synchronous request is logged, once: client errors (400/404) at INFO,
 * rejected requests (403/409/422/429, auth) at WARN, AI/provider failures at ERROR without a stack (the type
 * and message say what happened, and the AI client has already logged the call), and anything unexpected at
 * ERROR with the full stack trace. The request ID comes from the MDC.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Catches a MongoDB unique-index violation and reports it as a conflict.
     *
     * @param ex the duplicate-key exception thrown by the Mongo driver
     * @return an {@link ErrorResponse} with a generic "duplicate entry" message, at HTTP 409 Conflict
     */
    // Handle MongoDB Duplicate Key (Unique Constraint)
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateKey(DuplicateKeyException ex) {
        // The driver message quotes the duplicate value (often an email address), so it is not logged.
        // No message: a duplicate-key error quotes the duplicated value (e.g. a user's email).
        log.warn("event=request_rejected status=409 {} errorType={}", route(), ex.getClass().getSimpleName());
        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.CONFLICT.value(),
                "Conflict",
                "This record already exists (Duplicate Entry)"
        );
        return new ResponseEntity<>(error, HttpStatus.CONFLICT);
    }

    /**
     * Catches bean-validation failures on {@code @Valid} request bodies
     * (e.g. {@code @Email}, {@code @NotBlank} constraint violations) and
     * reports the first field error's default message.
     *
     * @param ex the validation exception raised by Spring's method argument binding
     * @return an {@link ErrorResponse} with the first field validation error's message, at HTTP 400 Bad Request
     */
    // Handle Validation Errors (e.g., @Email, @NotBlank)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        logClientError(ex);
        String msg = ex.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Validation Failed",
                msg
        );
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * Answers any of the application's own {@link ApiException}s with the status and error title the exception
     * carries, logged at the level its {@link ApiException.Outcome} calls for.
     *
     * @param ex the application exception
     * @return an {@link ErrorResponse} with {@code ex}'s message, sent with {@code ex.getStatus()}
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        switch (ex.getOutcome()) {
            case CLIENT_ERROR -> logClientError(ex);
            case REJECTED -> logRejected(ex);
            case UPSTREAM_FAILURE -> logUpstreamFailure(ex);
        }
        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(),
                ex.getStatus().value(),
                ex.getError(),
                ex.getMessage()
        );
        return new ResponseEntity<>(error, ex.getStatus());
    }

    /**
     * Catches a lookup that found no matching entity (e.g. an unknown
     * recipe or process id). This is the same exception type
     * {@code RecipeTemplateService}'s {@code findById(id).orElseThrow()}
     * calls already raised before this handler existed (previously falling
     * through to a generic 500); adding a handler for it here fixes that for
     * every existing caller as well as the new Recipe/Process endpoints.
     *
     * @param ex the not-found exception
     * @return an {@link ErrorResponse} with {@code ex}'s message, at HTTP 404 Not Found
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException ex) {
        logClientError(ex);
        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                ex.getMessage() != null ? ex.getMessage() : "The requested resource was not found"
        );
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    /**
     * Catches an argument a service rejected as invalid (an unknown id in a request body, a negative quantity,
     * ...). Services throw {@link IllegalArgumentException} for these, so they are the caller's mistake rather
     * than a server failure.
     *
     * @param ex the rejected-argument exception
     * @return an {@link ErrorResponse} with {@code ex}'s message, at HTTP 400 Bad Request
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        logClientError(ex);
        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                ex.getMessage() != null ? ex.getMessage() : "Invalid request"
        );
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * Last-resort handler for anything not mapped above. Exceptions Spring MVC or Spring Security already know
     * how to answer (malformed JSON, unknown path, wrong method, {@code @ResponseStatus} types, ...) are
     * rethrown untouched, which hands them back to Spring's default resolvers so their usual 4xx statuses are
     * kept. Everything else is a bug: logged with its stack trace and answered with a generic 500 that does not
     * leak the exception message.
     *
     * @param ex the unhandled exception
     * @return an {@link ErrorResponse} with a generic message, at HTTP 500 Internal Server Error
     * @throws Exception {@code ex} itself, when it is a framework exception with its own status mapping
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) throws Exception {
        if (isHandledByFramework(ex)) {
            log.debug("event=request_rejected {} errorType={} error=\"{}\"", route(), ex.getClass().getSimpleName(), FailureLogger.oneLine(ex.getMessage()));
            throw ex;
        }
        log.error("event=request_failed status=500 {} errorType={}", route(), ex.getClass().getSimpleName(), ex);
        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                "An unexpected error occurred"
        );
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static boolean isHandledByFramework(Exception ex) {
        return ex instanceof org.springframework.web.ErrorResponse
                || ex instanceof jakarta.servlet.ServletException
                || ex instanceof TypeMismatchException
                || ex instanceof HttpMessageNotReadableException
                || ex instanceof HttpMessageNotWritableException
                || ex instanceof BindException
                || ex instanceof AccessDeniedException
                || ex instanceof AuthenticationException
                || AnnotatedElementUtils.hasAnnotation(ex.getClass(), ResponseStatus.class);
    }

    /**
     * 400/404: the caller's mistake; INFO, so the operation log still says why a request did nothing. Only safe
     * detail is logged: validation errors list the offending field names (their messages echo the rejected values,
     * which can be personal data), lookups give their message (ids), and anything else just its type.
     */
    private static void logClientError(Exception ex) {
        String detail;
        if (ex instanceof MethodArgumentNotValidException invalid) {
            detail = "fields=" + invalid.getBindingResult().getFieldErrors().stream()
                    .map(org.springframework.validation.FieldError::getField).distinct().toList();
        } else if (ex instanceof NoSuchElementException) {
            detail = "error=\"" + FailureLogger.oneLine(ex.getMessage()) + "\"";
        } else {
            detail = "";
        }
        log.info("event=request_rejected {} errorType={} {}", route(), ex.getClass().getSimpleName(), detail);
    }

    /** Handled, expected-but-unwanted outcomes: forbidden, conflict, invalid AI output, capacity. */
    private static void logRejected(Exception ex) {
        log.warn("event=request_rejected {} errorType={} error=\"{}\"", route(), ex.getClass().getSimpleName(),
                FailureLogger.oneLine(ex.getMessage()));
    }

    /** A dependency (AI provider, model routing) failed; needs attention but the stack adds nothing. */
    private static void logUpstreamFailure(Exception ex) {
        log.error("event=request_failed {} errorType={} error=\"{}\"", route(), ex.getClass().getSimpleName(),
                FailureLogger.oneLine(ex.getMessage()));
    }

    /** {@code method=.. path=..} of the request being handled, so a rejection says which call it was. */
    private static String route() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return "method=" + request.getMethod() + " path=" + request.getRequestURI();
        }
        return "method=- path=-";
    }
}
