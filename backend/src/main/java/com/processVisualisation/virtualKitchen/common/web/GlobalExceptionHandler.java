package com.processVisualisation.virtualKitchen.common.web;

import com.processVisualisation.virtualKitchen.common.logging.FailureLogger;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Global exception handler for all REST controllers ({@code @RestControllerAdvice}).
 * Centralizes translation of both framework exceptions (Mongo duplicate keys,
 * bean-validation failures, rejected arguments, unreadable bodies) and this application's own
 * {@link ApiException}s into a consistent {@link ErrorResponse} JSON body with
 * an appropriate HTTP status and {@link ErrorCode}. Feature exceptions carry their own status, so
 * this class needs no knowledge of individual features.
 * <p>
 * Messages sent to clients are always ones this application wrote for users: exception internals, provider
 * errors and stack traces stay in the log.
 * <p>
 * This is also the one place a failed synchronous request is logged, once: client errors (400/404) at INFO,
 * rejected requests (403/409/422/429, auth) at WARN, AI/provider failures at ERROR without a stack (the type
 * and message say what happened, and the AI client has already logged the call), and anything unexpected at
 * ERROR with the full stack trace. The request ID comes from the MDC.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String VALIDATION_FALLBACK = "Some of the information sent isn't valid. Please check it and try again.";
    static final String UNREADABLE_BODY = "We couldn't read that request. Please refresh the page and try again.";
    static final String NOT_FOUND = "We couldn't find what you were looking for.";

    /**
     * Catches a MongoDB unique-index violation and reports it as a conflict.
     *
     * @param ex the duplicate-key exception thrown by the Mongo driver
     * @return an {@link ErrorResponse} with a generic "duplicate entry" message, at HTTP 409 Conflict
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateKey(DuplicateKeyException ex) {
        // No message: a duplicate-key error quotes the duplicated value (e.g. a user's email).
        log.warn("event=request_rejected status=409 {} errorType={}", route(), ex.getClass().getSimpleName());
        return respond(HttpStatus.CONFLICT, "Conflict", "This record already exists.", ErrorCode.CONFLICT);
    }

    /**
     * Catches bean-validation failures on {@code @Valid} request bodies (field or class-level constraints) and
     * reports the first error's message, which the DTO's annotation wrote for users.
     *
     * @param ex the validation exception raised by Spring's method argument binding
     * @return an {@link ErrorResponse} with the first validation error's message, at HTTP 400 Bad Request
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        logClientError(ex);
        // Field errors first, then class-level (e.g. @AssertTrue) errors; a request can have only the latter.
        String msg = Stream.concat(ex.getBindingResult().getFieldErrors().stream(),
                        ex.getBindingResult().getGlobalErrors().stream())
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(VALIDATION_FALLBACK);
        return respond(HttpStatus.BAD_REQUEST, "Validation Failed", msg, ErrorCode.VALIDATION_FAILED);
    }

    /**
     * Catches constraint failures on controller method parameters ({@code @RequestParam}, path variables,
     * {@code @Valid} lists).
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException ex) {
        logClientError(ex);
        String msg = ex.getAllErrors().stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(VALIDATION_FALLBACK);
        return respond(HttpStatus.BAD_REQUEST, "Validation Failed", msg, ErrorCode.VALIDATION_FAILED);
    }

    /** Catches constraint failures raised by programmatic or service-level ({@code @Validated}) validation. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        logClientError(ex);
        String msg = ex.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(VALIDATION_FALLBACK);
        return respond(HttpStatus.BAD_REQUEST, "Validation Failed", msg, ErrorCode.VALIDATION_FAILED);
    }

    /** Malformed JSON, a wrong type in a field, or an empty body where one is required. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        // The JSON reader wraps the size filter's IOException; that one is a 413, not a malformed body.
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof RequestSizeLimitFilter.PayloadTooLargeException tooLarge) {
                return handlePayloadTooLarge(tooLarge);
            }
        }
        logClientError(ex);
        return respond(HttpStatus.BAD_REQUEST, "Bad Request", UNREADABLE_BODY, ErrorCode.BAD_REQUEST);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        logClientError(ex);
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type",
                "This kind of request isn't supported.", ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    /** A request body over the size limit, detected while it was being read (see {@link RequestSizeLimitFilter}). */
    @ExceptionHandler(RequestSizeLimitFilter.PayloadTooLargeException.class)
    public ResponseEntity<ErrorResponse> handlePayloadTooLarge(RequestSizeLimitFilter.PayloadTooLargeException ex) {
        logClientError(ex);
        return respond(HttpStatus.CONTENT_TOO_LARGE, "Payload Too Large", RequestSizeLimitFilter.MESSAGE,
                ErrorCode.PAYLOAD_TOO_LARGE);
    }

    /**
     * Answers any of the application's own {@link ApiException}s with the status, error title, code and client
     * message the exception carries, logged at the level its {@link ApiException.Outcome} calls for.
     *
     * @param ex the application exception
     * @return an {@link ErrorResponse} with {@code ex}'s client message, sent with {@code ex.getStatus()}
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        switch (ex.getOutcome()) {
            case CLIENT_ERROR -> logClientError(ex);
            case REJECTED -> logRejected(ex);
            case UPSTREAM_FAILURE -> logUpstreamFailure(ex);
        }
        return respond(ex.getStatus(), ex.getError(), ex.getClientMessage(), ex.getCode());
    }

    /**
     * Catches a lookup that found no matching entity (e.g. an unknown recipe or process id). The exception's
     * message names internal ids, so it is logged, and the client gets a generic message.
     *
     * @param ex the not-found exception
     * @return an {@link ErrorResponse} with a generic message, at HTTP 404 Not Found
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException ex) {
        logClientError(ex);
        return respond(HttpStatus.NOT_FOUND, "Not Found", NOT_FOUND, ErrorCode.NOT_FOUND);
    }

    /**
     * Catches an argument a service rejected as invalid (an unknown id in a request body, a negative quantity,
     * ...). Services throw {@link IllegalArgumentException} with messages written for users, so they are the
     * caller's mistake rather than a server failure.
     *
     * @param ex the rejected-argument exception
     * @return an {@link ErrorResponse} with {@code ex}'s message, at HTTP 400 Bad Request
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        logClientError(ex);
        return respond(HttpStatus.BAD_REQUEST, HttpStatus.BAD_REQUEST.getReasonPhrase(),
                ex.getMessage() != null ? ex.getMessage() : VALIDATION_FALLBACK, ErrorCode.VALIDATION_FAILED);
    }

    /**
     * Last-resort handler for anything not mapped above. Exceptions Spring MVC or Spring Security already know
     * how to answer (unknown path, wrong method, {@code @ResponseStatus} types, ...) are
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
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "Something went wrong on our side. Please try again.", ErrorCode.INTERNAL);
    }

    private static ResponseEntity<ErrorResponse> respond(HttpStatus status, String error, String message, ErrorCode code) {
        return new ResponseEntity<>(ErrorResponse.of(status, error, message, code), status);
    }

    private static boolean isHandledByFramework(Exception ex) {
        return ex instanceof org.springframework.web.ErrorResponse
                || ex instanceof jakarta.servlet.ServletException
                || ex instanceof TypeMismatchException
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
