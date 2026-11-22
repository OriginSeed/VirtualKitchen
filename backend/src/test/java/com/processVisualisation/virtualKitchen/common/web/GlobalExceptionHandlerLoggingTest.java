package com.processVisualisation.virtualKitchen.common.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiCommunicationException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiClientException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiTimeoutException;
import com.processVisualisation.virtualKitchen.common.logging.LogCapture;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeRevisionConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalExceptionHandlerLoggingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unexpectedExceptionIsLoggedOnceWithStackAndAnsweredGeneric500() throws Exception {
        try (LogCapture logs = LogCapture.of(GlobalExceptionHandler.class)) {
            ResponseEntity<ErrorResponse> response = handler.handleUnexpected(new IllegalStateException("internal detail"));

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody().getMessage()).doesNotContain("internal detail");
            ILoggingEvent event = single(logs);
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getFormattedMessage()).startsWith("event=request_failed status=500");
        }
    }

    @Test
    void frameworkExceptionsAreRethrownSoSpringKeepsTheirStatus() {
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("PATCH");
        try (LogCapture logs = LogCapture.of(GlobalExceptionHandler.class)) {
            assertThatThrownBy(() -> handler.handleUnexpected(ex)).isSameAs(ex);
            assertThat(logs.events()).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));
        }
    }

    @Test
    void upstreamAiFailureIsErrorWithoutStack() {
        try (LogCapture logs = LogCapture.of(GlobalExceptionHandler.class)) {
            handler.handleApiException(new AiCommunicationException("Gemini API request failed with status: 500"));

            ILoggingEvent event = single(logs);
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNull();
        }
    }

    @Test
    void rejectedRequestIsWarnWithoutStack() {
        try (LogCapture logs = LogCapture.of(GlobalExceptionHandler.class)) {
            handler.handleApiException(new RecipeAccessDeniedException("no permission"));

            ILoggingEvent event = single(logs);
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
        }
    }

    @Test
    void revisionConflictIsAnswered409AndLoggedAsRejected() {
        try (LogCapture logs = LogCapture.of(GlobalExceptionHandler.class)) {
            ResponseEntity<ErrorResponse> response = handler.handleApiException(
                    new RecipeRevisionConflictException("changed elsewhere"));

            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(response.getBody().getMessage()).isEqualTo("changed elsewhere");
            assertThat(single(logs).getLevel()).isEqualTo(Level.WARN);
        }
    }

    @Test
    void notFoundIsInfoWithItsReason() {
        try (LogCapture logs = LogCapture.of(GlobalExceptionHandler.class)) {
            handler.handleNotFound(new NoSuchElementException("recipe 9"));

            ILoggingEvent event = single(logs);
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("errorType=NoSuchElementException").contains("recipe 9");
        }
    }

    @Test
    void duplicateKeyMessageIsNotLoggedBecauseItQuotesTheValue() {
        try (LogCapture logs = LogCapture.of(GlobalExceptionHandler.class)) {
            handler.handleDuplicateKey(new DuplicateKeyException(
                    "E11000 duplicate key error collection: users index: email dup key: { email: \"person@example.org\" }"));

            assertThat(logs.allMessages()).doesNotContain("person@example.org").contains("status=409");
        }
    }

    @Test
    void apiExceptionIsAnsweredWithItsOwnStatusAndTitle() {
        ResponseEntity<ErrorResponse> response = handler.handleApiException(new AiTimeoutException("slow provider"));

        assertThat(response.getStatusCode().value()).isEqualTo(504);
        assertThat(response.getBody().getStatus()).isEqualTo(504);
        assertThat(response.getBody().getError()).isEqualTo("AI Timeout");
        // The provider's own text is logged, never sent to the user.
        assertThat(response.getBody().getMessage()).isEqualTo(AiClientException.CLIENT_MESSAGE).doesNotContain("slow provider");
        assertThat(response.getBody().getCode()).isEqualTo("AI_UNAVAILABLE");
    }

    @Test
    void rejectedArgumentIsAnswered400AndLoggedAsClientError() {
        try (LogCapture logs = LogCapture.of(GlobalExceptionHandler.class)) {
            ResponseEntity<ErrorResponse> response =
                    handler.handleIllegalArgument(new IllegalArgumentException("quantity must be positive"));

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().getMessage()).isEqualTo("quantity must be positive");
            assertThat(single(logs).getLevel()).isEqualTo(Level.INFO);
        }
    }

    private static ILoggingEvent single(LogCapture logs) {
        assertThat(logs.events()).hasSize(1);
        return logs.events().get(0);
    }
}
