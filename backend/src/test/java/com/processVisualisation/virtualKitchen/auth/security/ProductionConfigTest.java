package com.processVisualisation.virtualKitchen.auth.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Startup checks on security-sensitive configuration: CORS origins and the JWT secret. */
class ProductionConfigTest {

    @Test
    void corsOriginsAreTrimmedAndKept() {
        assertThat(SecurityConfig.validateOrigins(List.of(" https://app.example.com", "http://localhost:5173 ", "")))
                .containsExactly("https://app.example.com", "http://localhost:5173");
    }

    @Test
    void corsWildcardIsRefused() {
        assertThatThrownBy(() -> SecurityConfig.validateOrigins(List.of("*")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must not contain '*'");
        assertThatThrownBy(() -> SecurityConfig.validateOrigins(List.of("https://*.example.com")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void corsNeedsAtLeastOneOrigin() {
        assertThatThrownBy(() -> SecurityConfig.validateOrigins(List.of(" ")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shortJwtSecretFailsStartup() {
        assertThatThrownBy(() -> new JwtService("too-short", 1000))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new JwtService(null, 1000)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void longJwtSecretWorks() {
        JwtService service = new JwtService("x".repeat(32), 60_000);
        assertThat(service.extractUserId(service.generateToken(5L, "cook", com.processVisualisation.virtualKitchen.auth.model.UserType.values()[0])))
                .isEqualTo(5L);
    }
}
