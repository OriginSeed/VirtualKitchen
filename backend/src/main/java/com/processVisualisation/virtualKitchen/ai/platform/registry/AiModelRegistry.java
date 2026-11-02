package com.processVisualisation.virtualKitchen.ai.platform.registry;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * In-memory view over the AI model registry: the configured {@link AiModelProperties} baseline with any admin
 * runtime overrides applied on top (see {@link #apply}). This is the single place model selection/routing consults
 * to answer "which models exist for this capability" and "what does this model fall back to" — recipe and
 * visualization services never reference a provider name or tier directly.
 * <p>
 * Readers see one immutable snapshot at a time; {@link #apply} validates a complete replacement before swapping it
 * in, so a bad override can never leave the registry half-updated.
 */
@Component
public class AiModelRegistry {

    private final AiModelProperties properties;
    private volatile Snapshot snapshot;

    public AiModelRegistry(AiModelProperties properties) {
        this.properties = properties;
        this.snapshot = build(copies(properties.getModels()), new LinkedHashMap<>(properties.getDefaultModel()));
    }

    public Optional<ModelDefinition> find(String key) {
        return Optional.ofNullable(snapshot.byKey().get(key));
    }

    public List<ModelDefinition> byCapability(AiCapability capability) {
        return snapshot.byCapability().getOrDefault(capability, List.of());
    }

    /** Every model, in configured order. */
    public List<ModelDefinition> all() {
        return List.copyOf(snapshot.byKey().values());
    }

    /** The configured default model key for {@code capability}, or {@code null}. */
    public String defaultKeyFor(AiCapability capability) {
        return snapshot.defaults().get(capability.name());
    }

    /** Models users may pick themselves for {@code capability}: enabled and marked user-selectable. */
    public List<ModelDefinition> userSelectable(AiCapability capability) {
        return byCapability(capability).stream()
                .filter(ModelDefinition::isEnabled)
                .filter(ModelDefinition::isUserSelectable)
                .toList();
    }

    /**
     * Resolves the configured default model for a capability.
     *
     * @throws IllegalStateException if no default is configured, the configured
     *         key is unknown, or the configured default model is disabled —
     *         these are all operator configuration errors, not user-facing
     *         "no credits" conditions.
     */
    public ModelDefinition defaultFor(AiCapability capability) {
        String defaultKey = defaultKeyFor(capability);
        if (defaultKey == null) {
            throw new IllegalStateException("No default AI model configured for capability " + capability);
        }
        return find(defaultKey)
                .filter(ModelDefinition::isEnabled)
                .orElseThrow(() -> new IllegalStateException(
                        "Configured default model '" + defaultKey + "' for " + capability + " is missing or disabled"));
    }

    public Optional<ModelDefinition> fallbackFor(ModelDefinition model) {
        if (model.getFallbackModelKey() == null) {
            return Optional.empty();
        }
        return find(model.getFallbackModelKey()).filter(ModelDefinition::isEnabled);
    }

    /** The configured models, before any runtime override. Copies: callers may not change the baseline. */
    public List<ModelDefinition> baselineModels() {
        return copies(properties.getModels());
    }

    /** The configured default model key per capability, before any runtime override. */
    public Map<String, String> baselineDefaults() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(properties.getDefaultModel()));
    }

    /**
     * Replaces the live registry with the baseline plus {@code modelEdits} (applied to each model's copy, by key)
     * and {@code defaultOverrides} (capability name to model key).
     *
     * @throws IllegalArgumentException if the result is inconsistent: an unknown model, a default that is disabled
     *         or of the wrong capability, or a fallback that is unknown, of another capability, or cyclic. The live
     *         registry is left unchanged.
     */
    public void apply(Map<String, Consumer<ModelDefinition>> modelEdits, Map<String, String> defaultOverrides) {
        List<ModelDefinition> models = baselineModels();
        Set<String> keys = new HashSet<>();
        for (ModelDefinition model : models) {
            keys.add(model.getKey());
            Consumer<ModelDefinition> edit = modelEdits.get(model.getKey());
            if (edit != null) {
                edit.accept(model);
            }
        }
        for (String key : modelEdits.keySet()) {
            if (!keys.contains(key)) {
                throw new IllegalArgumentException("Unknown AI model '" + key + "'");
            }
        }
        Map<String, String> defaults = new LinkedHashMap<>(properties.getDefaultModel());
        defaults.putAll(defaultOverrides);

        Snapshot candidate = build(models, defaults);
        validate(candidate);
        this.snapshot = candidate;
    }

    private static void validate(Snapshot candidate) {
        for (Map.Entry<String, String> entry : candidate.defaults().entrySet()) {
            ModelDefinition model = candidate.byKey().get(entry.getValue());
            if (model == null) {
                throw new IllegalArgumentException("Default model '" + entry.getValue() + "' does not exist");
            }
            if (!model.getCapability().name().equals(entry.getKey())) {
                throw new IllegalArgumentException(
                        "Default model '" + model.getKey() + "' is not a " + entry.getKey() + " model");
            }
            if (!model.isEnabled()) {
                throw new IllegalArgumentException("Default model '" + model.getKey() + "' must be enabled");
            }
        }
        for (ModelDefinition model : candidate.byKey().values()) {
            Set<String> seen = new HashSet<>();
            ModelDefinition current = model;
            while (current.getFallbackModelKey() != null) {
                if (!seen.add(current.getKey())) {
                    throw new IllegalArgumentException("Fallback chain from '" + model.getKey() + "' loops");
                }
                ModelDefinition next = candidate.byKey().get(current.getFallbackModelKey());
                if (next == null) {
                    throw new IllegalArgumentException("Fallback model '" + current.getFallbackModelKey()
                            + "' of '" + current.getKey() + "' does not exist");
                }
                if (next.getCapability() != current.getCapability()) {
                    throw new IllegalArgumentException("Fallback of '" + current.getKey()
                            + "' must be another " + current.getCapability() + " model");
                }
                current = next;
            }
        }
    }

    private static List<ModelDefinition> copies(List<ModelDefinition> models) {
        return models.stream().map(ModelDefinition::copy).toList();
    }

    private static Snapshot build(List<ModelDefinition> models, Map<String, String> defaults) {
        Map<String, ModelDefinition> byKey = new LinkedHashMap<>();
        Map<AiCapability, List<ModelDefinition>> byCapability = new EnumMap<>(AiCapability.class);
        for (ModelDefinition model : models) {
            byKey.put(model.getKey(), model);
            byCapability.computeIfAbsent(model.getCapability(), c -> new ArrayList<>()).add(model);
        }
        byCapability.replaceAll((capability, list) -> List.copyOf(list));
        return new Snapshot(Collections.unmodifiableMap(byKey), Collections.unmodifiableMap(byCapability),
                Collections.unmodifiableMap(defaults));
    }

    private record Snapshot(
            Map<String, ModelDefinition> byKey,
            Map<AiCapability, List<ModelDefinition>> byCapability,
            Map<String, String> defaults) {
    }
}
