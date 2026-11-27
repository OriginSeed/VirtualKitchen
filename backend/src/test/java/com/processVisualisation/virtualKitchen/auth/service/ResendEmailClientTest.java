package com.processVisualisation.virtualKitchen.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The Resend REST call and how each kind of failure is classified. The API is mocked; nothing leaves the JVM. */
class ResendEmailClientTest {

    private static final String BASE_URL = "https://resend.example";
    private static final String API_KEY = "re_test_key";
    private static final String FROM = "Virtual Kitchen <no-reply@kitchen.example>";
    private static final String TO = "cook@example.test";

    private ResendProperties properties;
    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        properties = new ResendProperties();
        properties.setApiKey(API_KEY);
        properties.setBaseUrl(BASE_URL);
        builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private ResendEmailClient client() {
        return new ResendEmailClient(properties, builder);
    }

    @Test
    void send_postsTheEmailWithBearerKeyAndReturnsTheMessageId() {
        server.expect(requestTo(BASE_URL + "/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(jsonPath("$.from").value(FROM))
                .andExpect(jsonPath("$.to[0]").value(TO))
                .andExpect(jsonPath("$.subject").value("Subject"))
                .andExpect(jsonPath("$.text").value("Body 123456"))
                .andRespond(withSuccess("{\"id\":\"msg_1\"}", MediaType.APPLICATION_JSON));

        String id = client().send(FROM, TO, "Subject", "Body 123456");

        assertThat(id).isEqualTo("msg_1");
        server.verify();
    }

    @Test
    void send_rejectedByResend_isRejectedWithStatusAndErrorNameButNoMessageText() {
        server.expect(requestTo(BASE_URL + "/emails"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"statusCode\":422,\"name\":\"validation_error\",\"message\":\"Invalid `to` field: " + TO + "\"}"));

        EmailDeliveryException error = assertThrows(EmailDeliveryException.class,
                () -> client().send(FROM, TO, "Subject", "Body"));

        assertThat(error.getReason()).isEqualTo(EmailDeliveryException.Reason.REJECTED);
        assertThat(error.getStatus()).isEqualTo(422);
        assertThat(error.getProviderError()).isEqualTo("validation_error");
        assertThat(error.getMessage()).doesNotContain(TO).doesNotContain(API_KEY);
        assertThat(error.getCause()).isNull();
    }

    @Test
    void send_invalidApiKey_isRejected() {
        server.expect(requestTo(BASE_URL + "/emails"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        EmailDeliveryException error = assertThrows(EmailDeliveryException.class,
                () -> client().send(FROM, TO, "Subject", "Body"));

        assertThat(error.getReason()).isEqualTo(EmailDeliveryException.Reason.REJECTED);
        assertThat(error.getStatus()).isEqualTo(401);
        assertThat(error.getMessage()).doesNotContain(API_KEY);
    }

    @Test
    void send_resendServerError_isProviderError() {
        server.expect(requestTo(BASE_URL + "/emails"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        EmailDeliveryException error = assertThrows(EmailDeliveryException.class,
                () -> client().send(FROM, TO, "Subject", "Body"));

        assertThat(error.getReason()).isEqualTo(EmailDeliveryException.Reason.PROVIDER_ERROR);
        assertThat(error.getStatus()).isEqualTo(500);
    }

    @Test
    void send_connectionFailure_isNetwork() {
        server.expect(requestTo(BASE_URL + "/emails"))
                .andRespond(withException(new IOException("Connection refused")));

        EmailDeliveryException error = assertThrows(EmailDeliveryException.class,
                () -> client().send(FROM, TO, "Subject", "Body"));

        assertThat(error.getReason()).isEqualTo(EmailDeliveryException.Reason.NETWORK);
    }

    @Test
    void send_timeout_isNetwork() {
        server.expect(requestTo(BASE_URL + "/emails"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        EmailDeliveryException error = assertThrows(EmailDeliveryException.class,
                () -> client().send(FROM, TO, "Subject", "Body"));

        assertThat(error.getReason()).isEqualTo(EmailDeliveryException.Reason.NETWORK);
    }

    @Test
    void send_missingApiKey_failsWithoutCallingResend() {
        properties.setApiKey(" ");

        EmailDeliveryException error = assertThrows(EmailDeliveryException.class,
                () -> client().send(FROM, TO, "Subject", "Body"));

        assertThat(error.getReason()).isEqualTo(EmailDeliveryException.Reason.NOT_CONFIGURED);
        server.verify();
    }

    @Test
    void send_missingSender_failsWithoutCallingResend() {
        EmailDeliveryException error = assertThrows(EmailDeliveryException.class,
                () -> client().send(null, TO, "Subject", "Body"));

        assertThat(error.getReason()).isEqualTo(EmailDeliveryException.Reason.NOT_CONFIGURED);
        server.verify();
    }

    @Test
    void send_readsTheKeyOnEverySend_soAnAdminOverrideAppliesAtOnce() {
        ResendEmailClient client = client();
        properties.setApiKey("re_rotated_key");
        server.expect(requestTo(BASE_URL + "/emails"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer re_rotated_key"))
                .andRespond(withSuccess("{\"id\":\"msg_2\"}", MediaType.APPLICATION_JSON));

        assertThat(client.send(FROM, TO, "Subject", "Body")).isEqualTo("msg_2");
        server.verify();
    }
}
