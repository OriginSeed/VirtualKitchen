package com.processVisualisation.virtualKitchen.exception;

import com.processVisualisation.virtualKitchen.ai.exception.AIAuthenticationException;
import com.processVisualisation.virtualKitchen.ai.exception.AIClientException;
import com.processVisualisation.virtualKitchen.ai.exception.AICommunicationException;
import com.processVisualisation.virtualKitchen.ai.exception.AIInvalidResponseException;
import com.processVisualisation.virtualKitchen.ai.exception.AITimeoutException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // Handle MongoDB Duplicate Key (Unique Constraint)
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateKey(DuplicateKeyException ex) {
        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.CONFLICT.value(),
                "Conflict",
                "This record already exists (Duplicate Entry)"
        );
        return new ResponseEntity<>(error, HttpStatus.CONFLICT);
    }

    // Handle MongoDB Duplicate Key (Unique Constraint)
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFoundException(UserNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.CONFLICT.value(),
                "Conflict",
                ex.getMessage()
        );
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    // Handle Validation Errors (e.g., @Email, @NotBlank)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Validation Failed",
                msg
        );
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(AIAuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAIAuthentication(AIAuthenticationException ex) {
        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.UNAUTHORIZED.value(),
                "AI Authentication Failed",
                ex.getMessage()
        );
        return new ResponseEntity<>(error, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(AITimeoutException.class)
    public ResponseEntity<ErrorResponse> handleAITimeout(AITimeoutException ex) {
        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.GATEWAY_TIMEOUT.value(),
                "AI Timeout",
                ex.getMessage()
        );
        return new ResponseEntity<>(error, HttpStatus.GATEWAY_TIMEOUT);
    }

    @ExceptionHandler(AIInvalidResponseException.class)
    public ResponseEntity<ErrorResponse> handleAIInvalidResponse(AIInvalidResponseException ex) {
        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_GATEWAY.value(),
                "AI Invalid Response",
                ex.getMessage()
        );
        return new ResponseEntity<>(error, HttpStatus.BAD_GATEWAY);
    }

    @ExceptionHandler(AICommunicationException.class)
    public ResponseEntity<ErrorResponse> handleAICommunication(AICommunicationException ex) {
        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_GATEWAY.value(),
                "AI Communication Failed",
                ex.getMessage()
        );
        return new ResponseEntity<>(error, HttpStatus.BAD_GATEWAY);
    }

    @ExceptionHandler(AIClientException.class)
    public ResponseEntity<ErrorResponse> handleAIGeneric(AIClientException ex) {
        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "AI Client Error",
                ex.getMessage()
        );
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
