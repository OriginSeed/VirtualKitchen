package com.processVisualisation.virtualKitchen.auth.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Connection settings for the Resend transactional email API ({@code app.mail.resend.*}). The sender address is
 * {@link AppMailProperties#getFrom()}.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.mail.resend")
public class ResendProperties {

    /** Resend API key ({@code RESEND_API_KEY}). Read on every send, so an admin-panel override applies at once. */
    private volatile String apiKey;

    /** Base URL of the Resend API. */
    private String baseUrl = "https://api.resend.com";

    /** Connect and read timeout for one send, in milliseconds. */
    private long timeoutMs = 10_000L;
}
