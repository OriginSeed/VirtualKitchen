package com.processVisualisation.virtualKitchen.admin.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One setting key and its value, as stored in Mongo. Setting keys contain dots ({@code credits.monthlyAllocation},
 * {@code model.gemini-flash.creditCost}), which Mongo can't take as map keys, so settings are stored as a list of
 * these rather than as a map.
 */
public record ConfigEntry(String key, String value) {

    public static List<ConfigEntry> listOf(Map<String, String> values) {
        List<ConfigEntry> entries = new ArrayList<>();
        if (values != null) {
            values.forEach((key, value) -> entries.add(new ConfigEntry(key, value)));
        }
        return entries;
    }

    /** The entries as a map, in stored order. */
    public static Map<String, String> mapOf(List<ConfigEntry> entries) {
        Map<String, String> values = new LinkedHashMap<>();
        if (entries != null) {
            entries.forEach(entry -> values.put(entry.key(), entry.value()));
        }
        return values;
    }
}
