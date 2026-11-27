package com.processVisualisation.virtualKitchen.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Sends plain-text transactional emails through the Resend REST API
 * ({@code POST /emails}, see https://resend.com/docs/api-reference/emails/send-email).
 * <p>
 * The API key is read from {@link ResendProperties} on every send. Failures surface as
 * {@link EmailDeliveryException} carrying only the reason, the HTTP status and Resend's error name, never the
 * key, the recipient or the content.
 */
@Component
public class ResendEmailClient {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailClient.class);

    private final ResendProperties properties;
    private final RestClient restClient;

    @Autowired
    public ResendEmailClient(ResendProperties properties) {
        this(properties, defaultBuilder(properties));
    }

    /** For tests: {@code builder} already carries the base URL (and a mock server). */
    ResendEmailClient(ResendProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.build();
        if (isBlank(properties.getApiKey())) {
            log.warn("event=email_provider_not_configured provider=resend missing=RESEND_API_KEY");
        }
    }

    /**
     * Sends one plain-text email.
     *
     * @return the id Resend assigned to the email (may be null if Resend omitted it)
     * @throws EmailDeliveryException if the key or sender is missing, Resend refuses or fails, or it can't be reached
     */
    public String send(String from, String to, String subject, String text) {
        String apiKey = properties.getApiKey();
        if (isBlank(apiKey) || isBlank(from)) {
            throw new EmailDeliveryException(EmailDeliveryException.Reason.NOT_CONFIGURED, 0, null, null);
        }

        Map<String, Object> body = Map.of(
                "from", from,
                "to", List.of(to),
                "subject", subject,
                "text", text);

        try {
            Map<?, ?> response = restClient.post()
                    .uri("/emails")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            Object id = response != null ? response.get("id") : null;
            return id != null ? id.toString() : null;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            EmailDeliveryException.Reason reason = e.getStatusCode().is4xxClientError()
                    ? EmailDeliveryException.Reason.REJECTED
                    : EmailDeliveryException.Reason.PROVIDER_ERROR;
            // The exception (and its body) is not chained: Resend's message text can echo the recipient address.
            throw new EmailDeliveryException(reason, status, errorName(e), null);
        } catch (ResourceAccessException e) {
            throw new EmailDeliveryException(EmailDeliveryException.Reason.NETWORK, 0, null, null);
        } catch (RestClientException e) {
            throw new EmailDeliveryException(EmailDeliveryException.Reason.PROVIDER_ERROR, 0, null, null);
        }
    }

    /** Resend's machine-readable error {@code name} (e.g. {@code validation_error}), or null. */
    private static String errorName(RestClientResponseException e) {
        try {
            Map<?, ?> error = e.getResponseBodyAs(Map.class);
            Object name = error != null ? error.get("name") : null;
            return name instanceof String s && s.matches("[A-Za-z0-9_]{1,64}") ? s : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static RestClient.Builder defaultBuilder(ResendProperties properties) {
        Duration timeout = Duration.ofMillis(Math.max(1L, properties.getTimeoutMs()));
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
