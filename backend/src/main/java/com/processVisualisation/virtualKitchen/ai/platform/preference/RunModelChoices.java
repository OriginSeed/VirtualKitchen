package com.processVisualisation.virtualKitchen.ai.platform.preference;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Runs one user-started AI action with the models the user picked for it: checks the choices, then starts the
 * action inside {@link AiRunModels} so every AI request it makes — on whichever task pool — uses them. Background
 * work must be submitted before {@code start} returns (the pools copy the MDC at submission).
 */
@Component
public class RunModelChoices {

    private final PreferredModelResolver resolver;

    public RunModelChoices(PreferredModelResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * @param modelKeys capability name to model key; blank entries (and null) keep the account setting
     * @throws IllegalArgumentException for an unknown capability or a model users can't choose
     */
    public <T> T startWith(Map<String, String> modelKeys, Supplier<T> start) {
        Map<String, String> choices = resolver.validateRunChoices(modelKeys);
        try (AiRunModels.Scope ignored = AiRunModels.open(choices)) {
            return start.get();
        }
    }
}
