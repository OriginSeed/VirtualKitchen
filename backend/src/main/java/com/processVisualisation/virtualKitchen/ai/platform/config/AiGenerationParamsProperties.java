package com.processVisualisation.virtualKitchen.ai.platform.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Sampling parameters for each text call site ({@code ai.generation.*}). Defaults are the values the services used
 * when these were hard-coded. Read on every request, so admin runtime overrides apply to the next call.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.generation")
public class AiGenerationParamsProperties {

    /** Recipe process generation: near-deterministic, and a whole process needs room. */
    private Call processGeneration = new Call(0.1, 10000);

    /** Recipe process edits (a diff against the saved process). */
    private Call processEdit = new Call(0.1, 4000);

    /** One step's image prompt. */
    private Call visualization = new Call(0.4, 2000);

    /** Image prompts for a batch of steps in one call. */
    private PromptBatch promptBatch = new PromptBatch();

    /** The free-form AI chat endpoint. */
    private Call chat = new Call(0.2, 300);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Call {
        private volatile double temperature;
        private volatile int maxTokens;
    }

    @Data
    public static class PromptBatch {
        private volatile double temperature = 0.4;
        /** Output budget for the batch's shared part, plus {@link #maxTokensPerTarget} per step, up to the cap. */
        private volatile int baseMaxTokens = 800;
        private volatile int maxTokensPerTarget = 300;
        private volatile int maxTokensCap = 8000;

        public int maxTokensFor(int targets) {
            return Math.min(maxTokensCap, baseMaxTokens + maxTokensPerTarget * targets);
        }
    }
}
