package com.processVisualisation.virtualKitchen.ai.provider.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Spring configuration that builds and registers the {@link RestClient}
 * beans used by every outbound AI/storage client in this application
 * (Draw Things, Gemini, Ollama, OpenAI, DeepInfra, and Supabase). Each bean is
 * configured with the provider's base URL and connect/read timeout, both
 * sourced from that provider's {@code *Properties} configuration class.
 */
@Configuration
public class AiRestClientConfig {

    // 1. DRAWTHINGS CLIENT (Resolves your startup crash)
    /**
     * Builds the {@link RestClient} used to call the Draw Things image
     * generation service.
     *
     * @param properties the configured Draw Things base URL and timeout
     * @return a {@link RestClient} configured for Draw Things, registered as {@code drawThingsRestClient}
     */
    @Bean("drawThingsRestClient")
    public RestClient drawThingsRestClient(DrawThingsProperties properties) {
        long configuredTimeout = properties.getTimeoutMs() == null ? 60000L : properties.getTimeoutMs();
        return createRestClient(properties.getBaseUrl(), configuredTimeout);
    }

    // 2. GEMINI CLIENT
    /**
     * Builds the {@link RestClient} used to call the Gemini API.
     *
     * @param properties the configured Gemini base URL and timeout
     * @return a {@link RestClient} configured for Gemini, registered as {@code geminiRestClient}
     */
    @Bean("geminiRestClient")
    public RestClient geminiRestClient(GeminiProperties properties) {
        long configuredTimeout = properties.getTimeoutMs() == null ? 30000L : properties.getTimeoutMs();
        return createRestClient(properties.getBaseUrl(), configuredTimeout);
    }

    // 3. OLLAMA CLIENT
    /**
     * Builds the {@link RestClient} used to call the Ollama API.
     *
     * @param properties the configured Ollama base URL and timeout
     * @return a {@link RestClient} configured for Ollama, registered as {@code ollamaRestClient}
     */
    @Bean("ollamaRestClient")
    public RestClient ollamaRestClient(OllamaProperties properties) {
        long configuredTimeout = properties.getTimeoutMs() == null ? 120000L : properties.getTimeoutMs();
        return createRestClient(properties.getBaseUrl(), configuredTimeout);
    }

    // 4. OPENAI CLIENT
    /**
     * Builds the {@link RestClient} used to call the OpenAI API.
     *
     * @param properties the configured OpenAI base URL and timeout
     * @return a {@link RestClient} configured for OpenAI, registered as {@code openAiRestClient}
     */
    @Bean("openAiRestClient")
    public RestClient openAiRestClient(OpenAIProperties properties) {
        long configuredTimeout = properties.getTimeoutMs() == null ? 30000L : properties.getTimeoutMs();
        return createRestClient(properties.getBaseUrl(), configuredTimeout);
    }

    // 5. DEEPINFRA CLIENT
    /**
     * Builds the {@link RestClient} used to call DeepInfra's OpenAI-compatible API.
     *
     * @param properties the configured DeepInfra base URL and timeout
     * @return a {@link RestClient} configured for DeepInfra, registered as {@code deepInfraRestClient}
     */
    @Bean("deepInfraRestClient")
    public RestClient deepInfraRestClient(DeepInfraProperties properties) {
        long configuredTimeout = properties.getTimeoutMs() == null ? 120000L : properties.getTimeoutMs();
        return createRestClient(properties.getBaseUrl(), configuredTimeout);
    }

    /**
     * Builds the {@link RestClient} used to call the self-hosted text-to-speech engine.
     *
     * @param properties the configured local TTS base URL and timeout
     * @return a {@link RestClient} configured for local TTS, registered as {@code localTtsRestClient}
     */
    @Bean("localTtsRestClient")
    public RestClient localTtsRestClient(LocalTtsProperties properties) {
        long configuredTimeout = properties.getTimeoutMs() == null ? 60000L : properties.getTimeoutMs();
        return createRestClient(properties.getBaseUrl(), configuredTimeout);
    }

    /**
     * Builds the {@link RestClient} used to call the Supabase Storage API.
     *
     * @param properties the configured Supabase URL and timeout
     * @return a {@link RestClient} configured for Supabase, qualified as {@code supabaseRestClient}
     */
    @Bean
    @Qualifier("supabaseRestClient")
    public RestClient supabaseRestClient(SupabaseProperties properties) {
        return createRestClient(properties.getUrl(), properties.getTimeoutMs());
    }

    // Helper method to eliminate duplicate timeout and builder logic
    /**
     * Creates a {@link RestClient} for the given base URL with a shared
     * connect and read timeout.
     *
     * @param baseUrl the base URL the client will send requests to
     * @param configuredTimeout the connect/read timeout in milliseconds (at least 1)
     * @return a configured {@link RestClient} instance
     */
    private RestClient createRestClient(String baseUrl, long configuredTimeout) {
        int timeout = Math.toIntExact(Math.max(1L, configuredTimeout));

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
