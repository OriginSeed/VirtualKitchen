package com.processVisualisation.virtualKitchen.admin.config;

import java.util.List;

/**
 * One admin-editable setting. Values are always strings ("true"/"false" for toggles); the admin UI renders a
 * dropdown of {@link #options()} for SELECT, a switch for TOGGLE, a text box for TEXT,
 * and a write-only field for SECRET.
 *
 * @param key      unique within its section, e.g. {@code model.gemini-flash.creditCost}
 * @param group    heading the field is shown under, e.g. the model's name
 * @param options  the only values accepted (empty for SECRET); an empty string option means "none"
 * @param baseline the configured value from application properties (masked for secrets)
 * @param current  the live value (masked for secrets)
 */
public record ConfigField(
        String key,
        String label,
        String group,
        Type type,
        List<String> options,
        String baseline,
        String current,
        boolean overridden,
        String help) {

    /** TEXT is for values that can't be a list (a sender address, an SMTP user name). */
    public enum Type { SELECT, TOGGLE, TEXT, SECRET }

    private static final int MAX_TEXT_LENGTH = 200;

    public static final List<String> BOOLEAN_OPTIONS = List.of("true", "false");

    public static ConfigField select(String key, String label, String group, List<String> options,
                                     Object baseline, Object current, boolean overridden, String help) {
        return new ConfigField(key, label, group, Type.SELECT, withValue(options, baseline, current),
                str(baseline), str(current), overridden, help);
    }

    public static ConfigField toggle(String key, String label, String group,
                                     boolean baseline, boolean current, boolean overridden, String help) {
        return new ConfigField(key, label, group, Type.TOGGLE, BOOLEAN_OPTIONS,
                String.valueOf(baseline), String.valueOf(current), overridden, help);
    }

    public static ConfigField text(String key, String label, String group,
                                   String baseline, String current, boolean overridden, String help) {
        return new ConfigField(key, label, group, Type.TEXT, List.of(), str(baseline), str(current), overridden, help);
    }

    public static ConfigField secret(String key, String label, String group,
                                     String maskedBaseline, String maskedCurrent, boolean overridden, String help) {
        return new ConfigField(key, label, group, Type.SECRET, List.of(), maskedBaseline, maskedCurrent,
                overridden, help);
    }

    /** Whether {@code value} may be written to this field. */
    public boolean accepts(String value) {
        if (type == Type.SECRET || type == Type.TEXT) {
            return value != null && !value.isBlank() && value.length() <= MAX_TEXT_LENGTH
                    && value.chars().noneMatch(Character::isISOControl);
        }
        return options.contains(value);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * The allowed list always includes the baseline and live values, so a configured value outside the curated list
     * still shows (and can be restored) instead of the dropdown silently picking something else.
     */
    private static List<String> withValue(List<String> options, Object baseline, Object current) {
        List<String> result = new java.util.ArrayList<>(options);
        for (String value : List.of(str(baseline), str(current))) {
            if (!result.contains(value)) {
                result.add(value);
            }
        }
        return List.copyOf(result);
    }
}
