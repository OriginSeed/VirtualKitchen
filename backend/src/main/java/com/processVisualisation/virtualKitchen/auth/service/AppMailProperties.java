package com.processVisualisation.virtualKitchen.auth.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** The app's own mail settings ({@code app.mail.*}); the Resend connection itself is {@link ResendProperties}. */
@Data
@Component
@ConfigurationProperties(prefix = "app.mail")
public class AppMailProperties {

    /** Sender address of the OTP emails. Read on every send. */
    private volatile String from;
}
