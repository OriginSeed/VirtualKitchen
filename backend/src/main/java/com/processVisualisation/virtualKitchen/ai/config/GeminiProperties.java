package com.processVisualisation.virtualKitchen.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "ai.gemini")
public class GeminiProperties {

    private String apiKey;
    private String baseUrl;
    private String chatEndpoint;
    private String defaultModel;
    private Long timeoutMs;
}
