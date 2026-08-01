package com.processVisualisation.virtualKitchen.ai.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class GeminiRestClientConfig {

    @Bean
    @Qualifier("geminiRestClient")
    public RestClient geminiRestClient(GeminiProperties properties) {
        long configuredTimeout = properties.getTimeoutMs() == null ? 30000L : properties.getTimeoutMs();
        int timeout = Math.toIntExact(Math.max(1L, configuredTimeout));

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout));

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
