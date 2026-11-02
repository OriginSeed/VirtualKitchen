package com.processVisualisation.virtualKitchen.admin.config;

import java.util.List;
import java.util.Map;

/**
 * A group of admin-editable runtime settings (models, thread pools, mail…). Each section owns its baseline (the
 * values configured in application properties, captured at startup) and knows how to apply a set of overrides to
 * the live beans without a restart.
 * <p>
 * {@link RuntimeConfigService} stores the overrides and calls {@link #apply} with the section's complete override
 * map every time, so applying is idempotent: a key missing from the map means "back to the baseline".
 */
public interface ConfigSection {

    /** Stable id used in URLs and as the stored document id, e.g. {@code models}. */
    String id();

    String label();

    String description();

    /** Every editable field with its baseline and live values. Live values reflect {@code overrides}. */
    List<ConfigField> fields(Map<String, String> overrides);

    /**
     * Applies {@code overrides} (key to value, already checked against {@link ConfigField#accepts}) to the live
     * beans; keys not present go back to the baseline.
     *
     * @throws IllegalArgumentException when the combination is invalid; the live config must then be unchanged
     */
    void apply(Map<String, String> overrides);

    /** False when the section can't be edited in this deployment; {@link #unavailableReason()} says why. */
    default boolean available() {
        return true;
    }

    default String unavailableReason() {
        return null;
    }

    /** Converts a submitted value to what is stored (e.g. encrypts a secret). */
    default String toStored(String key, String value) {
        return value;
    }

    /** Converts a stored value back before {@link #apply} (e.g. decrypts a secret). */
    default String fromStored(String key, String value) {
        return value;
    }

    /** What the audit log records for a value; secrets must be masked. */
    default String forAudit(String key, String value) {
        return value;
    }
}
