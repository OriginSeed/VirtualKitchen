package com.processVisualisation.virtualKitchen.ai.platform.preference;

import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The models one run (an AI Recipe Creation) was started with, overriding the user's saved choices for just that
 * run. Carried in the MDC like the workflow id ({@code AiAnalyticsRecorder.WORKFLOW_ID_MDC}): {@code TaskPool}
 * copies the submitting thread's MDC onto its workers, so the choice reaches every AI request the run's jobs make,
 * however many pools deep.
 * <p>
 * Work restarted outside the run (e.g. a job resumed after a restart) has no run choice and falls back to the
 * user's saved choice, then the default.
 */
public final class AiRunModels {

    static final String MDC_PREFIX = "aiRunModel.";

    private AiRunModels() {
    }

    /** The run's model key for {@code capability}, if the current run chose one. */
    public static Optional<String> keyFor(AiCapability capability) {
        String key = MDC.get(MDC_PREFIX + capability.name());
        return key == null || key.isBlank() ? Optional.empty() : Optional.of(key);
    }

    /**
     * Puts {@code modelKeys} (capability name to model key) in scope until the returned scope is closed, which
     * restores whatever was there before. Null or empty means "no run choice".
     */
    public static Scope open(Map<String, String> modelKeys) {
        Map<String, String> previous = new LinkedHashMap<>();
        if (modelKeys != null) {
            modelKeys.forEach((capability, key) -> {
                String mdcKey = MDC_PREFIX + capability;
                previous.put(mdcKey, MDC.get(mdcKey));
                if (key == null || key.isBlank()) {
                    MDC.remove(mdcKey);
                } else {
                    MDC.put(mdcKey, key);
                }
            });
        }
        return () -> previous.forEach((mdcKey, value) -> {
            if (value == null) {
                MDC.remove(mdcKey);
            } else {
                MDC.put(mdcKey, value);
            }
        });
    }

    /** An open run choice; closing it never throws. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
