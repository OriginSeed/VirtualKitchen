package com.processVisualisation.virtualKitchen.auth.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * One-time passcode limits ({@code app.otp.*}). Read on every request, so admin runtime overrides apply to the
 * next code issued or verified.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.otp")
public class OtpProperties {

    /** Minutes a code stays valid. */
    private volatile long expirationMinutes = 10;

    /** Wrong guesses allowed per code before it is locked. */
    private volatile int maxAttempts = 5;

    /** Rolling window, in minutes, for the resend limit. */
    private volatile long resendWindowMinutes = 15;

    /** Codes that may be issued per email and purpose within the window. */
    private volatile int maxPerWindow = 5;
}
