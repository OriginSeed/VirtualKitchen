package com.processVisualisation.virtualKitchen.ai.platform.preference;

import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelRegistry;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads and saves the user's own model choices, and resolves which model a user's request runs on when the caller
 * names none: the user's choice while it is still enabled and user-selectable, else the admin's default. A stale
 * choice is ignored, never an error, so disabling a model never breaks a user's requests.
 */
@Component
public class PreferredModelResolver {

    private final UserAiPreferencesRepository repository;
    private final AiModelRegistry registry;

    public PreferredModelResolver(UserAiPreferencesRepository repository, AiModelRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    /** The user's usable choice for {@code capability}, if they made one. */
    public Optional<ModelDefinition> preferred(Long userId, AiCapability capability) {
        if (userId == null) {
            return Optional.empty();
        }
        return repository.findById(userId)
                .map(prefs -> prefs.getModelKeys().get(capability.name()))
                .flatMap(key -> usable(key, capability));
    }

    /**
     * The model a request of the user's runs on before any credit fallback: the current run's choice (see
     * {@link AiRunModels}), else the user's saved choice, else the default. Choices no longer offered are skipped.
     */
    public ModelDefinition resolve(Long userId, AiCapability capability) {
        return AiRunModels.keyFor(capability).flatMap(key -> usable(key, capability))
                .or(() -> preferred(userId, capability))
                .orElseGet(() -> registry.defaultFor(capability));
    }

    /**
     * Checks a run's model choices (capability name to model key) before the run starts, and returns them without
     * blank entries.
     *
     * @throws IllegalArgumentException for an unknown capability or a model users can't choose for it
     */
    public Map<String, String> validateRunChoices(Map<String, String> modelKeys) {
        Map<String, String> valid = new LinkedHashMap<>();
        if (modelKeys == null) {
            return valid;
        }
        modelKeys.forEach((capability, key) -> {
            if (key == null || key.isBlank()) {
                return;
            }
            AiCapability parsed = AiCapability.valueOf(capability);
            if (usable(key, parsed).isEmpty()) {
                throw new IllegalArgumentException("'" + key + "' is not an available " + capability + " model");
            }
            valid.put(capability, key);
        });
        return valid;
    }

    /** The user's saved choices as stored (possibly stale), keyed by capability name. */
    public Map<String, String> saved(Long userId) {
        return repository.findById(userId).map(UserAiPreferences::getModelKeys).orElseGet(LinkedHashMap::new);
    }

    /**
     * Replaces the user's choices for the given capabilities; a null or blank key clears the choice.
     *
     * @throws IllegalArgumentException if a model is unknown, of another capability, disabled, or not offered to users
     */
    public Map<String, String> save(Long userId, Map<AiCapability, String> choices) {
        UserAiPreferences prefs = repository.findById(userId).orElseGet(() -> {
            UserAiPreferences created = new UserAiPreferences();
            created.setUserId(userId);
            return created;
        });
        for (Map.Entry<AiCapability, String> choice : choices.entrySet()) {
            String key = choice.getValue();
            if (key == null || key.isBlank()) {
                prefs.getModelKeys().remove(choice.getKey().name());
                continue;
            }
            if (usable(key, choice.getKey()).isEmpty()) {
                throw new IllegalArgumentException("'" + key + "' is not an available "
                        + choice.getKey() + " model");
            }
            prefs.getModelKeys().put(choice.getKey().name(), key);
        }
        prefs.setUpdatedAt(LocalDateTime.now());
        return repository.save(prefs).getModelKeys();
    }

    /** Whether {@code key} is a model users may pick for {@code capability} right now. */
    public Optional<ModelDefinition> usable(String key, AiCapability capability) {
        return registry.find(key)
                .filter(model -> model.getCapability() == capability)
                .filter(ModelDefinition::isEnabled)
                .filter(ModelDefinition::isUserSelectable);
    }
}
