package com.processVisualisation.virtualKitchen.ai.platform.registry;

import lombok.Data;

/**
 * One entry in the AI model registry, bound from the {@code ai.models[*]}
 * configuration list. Mirrors the existing per-provider {@code *Properties}
 * classes in {@code restclient.config} rather than replacing them:
 * {@link #getProviderModelId()} is the same model id already stored in e.g.
 * {@code GeminiProperties.defaultModel}/{@code OllamaProperties.defaultModel},
 * and {@link #getProviderBean()} names the {@code AiClient}/{@code
 * ImageGenerationClient} Spring bean (see {@code ai.dispatch.AiClientResolver})
 * that should be invoked for this model.
 */
@Data
public class ModelDefinition {

    /** Stable registry key, e.g. {@code "gemini-flash"}, {@code "ollama-qwen"}. */
    private String key;

    private AiCapability capability;

    private ModelTier tier;

    /** Bean name of the {@code AiClient} or {@code ImageGenerationClient} to invoke. */
    private String providerBean;

    /** The model id passed to the provider (e.g. {@code "qwen3:4b"}, {@code "gemini-3.5-flash"}). */
    private String providerModelId;

    /** Credits consumed per successful request. Always {@code 0} for {@link ModelTier#OPEN_SOURCE}. */
    private int creditCost;

    /** Registry key of the model to fall back to when this one's credits are exhausted, or {@code null}. */
    private String fallbackModelKey;

    /**
     * Provider list prices in USD per 1M tokens, used only to estimate what a request cost (providers return token
     * counts, never a price). Null leaves the estimate unknown; set them to 0 for a local/free model.
     */
    private Double inputUsdPerMillion;
    private Double outputUsdPerMillion;
    /** Price of prompt tokens served from the provider's context cache; falls back to the input price when null. */
    private Double cachedInputUsdPerMillion;

    /** Operator kill-switch; a disabled model is never selected. */
    private boolean enabled = true;

    /** Name shown to users in the model picker; falls back to {@link #getKey()} when unset. */
    private String displayName;

    /** Whether users may pick this model themselves (their saved preference or a per-run override). */
    private boolean userSelectable;

    public String displayNameOrKey() {
        return displayName != null && !displayName.isBlank() ? displayName : key;
    }

    /** A detached copy, so runtime overrides never mutate the configured baseline. */
    public ModelDefinition copy() {
        ModelDefinition copy = new ModelDefinition();
        copy.setKey(key);
        copy.setCapability(capability);
        copy.setTier(tier);
        copy.setProviderBean(providerBean);
        copy.setProviderModelId(providerModelId);
        copy.setCreditCost(creditCost);
        copy.setFallbackModelKey(fallbackModelKey);
        copy.setInputUsdPerMillion(inputUsdPerMillion);
        copy.setOutputUsdPerMillion(outputUsdPerMillion);
        copy.setCachedInputUsdPerMillion(cachedInputUsdPerMillion);
        copy.setEnabled(enabled);
        copy.setDisplayName(displayName);
        copy.setUserSelectable(userSelectable);
        return copy;
    }
}
