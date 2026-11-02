package com.processVisualisation.virtualKitchen.admin.config.sections;

import com.processVisualisation.virtualKitchen.admin.config.ConfigField;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Builders and parsers shared by the config sections: a field's live value is its override, else its baseline. */
final class Fields {

    private Fields() {
    }

    static ConfigField select(String key, String label, String group, List<String> options, Object baseline,
                              Map<String, String> overrides, String help) {
        String base = baseline == null ? "" : String.valueOf(baseline);
        return ConfigField.select(key, label, group, options, base, overrides.getOrDefault(key, base),
                overrides.containsKey(key), help);
    }

    static ConfigField toggle(String key, String label, String group, boolean baseline,
                              Map<String, String> overrides, String help) {
        boolean current = overrides.containsKey(key) ? Boolean.parseBoolean(overrides.get(key)) : baseline;
        return ConfigField.toggle(key, label, group, baseline, current, overrides.containsKey(key), help);
    }

    static List<String> values(Object... values) {
        return Arrays.stream(values).map(String::valueOf).toList();
    }

    static int intValue(Map<String, String> overrides, String key, int baseline) {
        String value = overrides.get(key);
        return value == null ? baseline : Integer.parseInt(value);
    }

    static long longValue(Map<String, String> overrides, String key, long baseline) {
        String value = overrides.get(key);
        return value == null ? baseline : Long.parseLong(value);
    }

    static double doubleValue(Map<String, String> overrides, String key, double baseline) {
        String value = overrides.get(key);
        return value == null ? baseline : Double.parseDouble(value);
    }

    /** An optional integer: "" (or no override of a null baseline) means unset. */
    static Integer optionalInt(Map<String, String> overrides, String key, Integer baseline) {
        if (!overrides.containsKey(key)) {
            return baseline;
        }
        String value = overrides.get(key);
        return value.isBlank() ? null : Integer.parseInt(value);
    }

    /** Fails on override keys the section doesn't know, so a stale stored key is reported rather than ignored. */
    static void requireKnown(Map<String, String> overrides, List<ConfigField> fields) {
        for (String key : overrides.keySet()) {
            if (fields.stream().noneMatch(field -> field.key().equals(key))) {
                throw new IllegalArgumentException("Unknown setting '" + key + "'");
            }
        }
    }

    /** Runs {@code action} for an override key when it is present. */
    static void ifPresent(Map<String, String> overrides, String key, Consumer<String> action) {
        String value = overrides.get(key);
        if (value != null) {
            action.accept(value);
        }
    }
}
