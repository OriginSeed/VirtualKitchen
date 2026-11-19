package com.processVisualisation.virtualKitchen.ai.recipe.narration;

import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationInput;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Turns one step's saved content into the sentence that is spoken, and fingerprints it.
 * <p>
 * The script is built deterministically from the same extracted step input the visualization
 * pipeline uses, so the same saved step always yields the same hash. The hash is what makes
 * narration reusable (same text means the existing audio is still valid) and what detects edits
 * (different text means the existing audio is stale). Bump {@link #SCRIPT_VERSION} whenever the
 * wording rules here change, so every narration built by the old rules goes stale on purpose.
 */
@Component
public class NarrationScriptBuilder {

    static final String SCRIPT_VERSION = "v1";

    private final NarrationProperties properties;

    public NarrationScriptBuilder(NarrationProperties properties) {
        this.properties = properties;
    }

    /** The spoken script and its fingerprint; {@code text} is empty when the step has nothing to say. */
    public record NarrationScript(String text, String hash) {
        public boolean narratable() {
            return !text.isEmpty();
        }
    }

    public NarrationScript build(RecipeProcessVisualizationInput input) {
        String text = truncate(normalize(compose(input)));
        return new NarrationScript(text, text.isEmpty() ? "" : hash(text));
    }

    private String compose(RecipeProcessVisualizationInput input) {
        // A step summary is already written to be spoken, continuing from the step before; it is said as it is.
        // Steps without one keep the composed script below, so their text (and their existing audio) is unchanged.
        String summary = clean(input.summary());
        if (!summary.isEmpty()) {
            return terminate(summary);
        }
        List<String> sentences = new ArrayList<>();

        String description = clean(input.actionDescription());
        if (!description.isEmpty()) {
            sentences.add(description);
        } else {
            String fallback = describeAction(input);
            if (!fallback.isEmpty()) {
                sentences.add(fallback);
            }
        }

        String duration = clean(input.duration());
        String temperature = spokenTemperature(clean(input.temperature()));
        String flame = clean(input.flameLevel());
        List<String> conditions = new ArrayList<>();
        if (!duration.isEmpty()) conditions.add("for " + duration);
        if (!temperature.isEmpty()) conditions.add("at " + temperature);
        if (!flame.isEmpty()) conditions.add("on " + flame.toLowerCase(Locale.ROOT) + " heat");
        if (!conditions.isEmpty() && !sentences.isEmpty()) {
            sentences.add("Do this " + String.join(", ", conditions));
        }

        String expected = clean(input.expectedOutput());
        if (!expected.isEmpty()) {
            sentences.add("Expected result: " + expected);
        }

        StringBuilder script = new StringBuilder();
        for (String sentence : sentences) {
            if (!script.isEmpty()) script.append(' ');
            script.append(terminate(sentence));
        }
        return script.toString();
    }

    /** "Chop onion and garlic" from the action label and its targets, when there is no description. */
    private String describeAction(RecipeProcessVisualizationInput input) {
        String action = clean(input.action());
        if (action.isEmpty()) {
            return "";
        }
        List<String> targets = new ArrayList<>();
        if (input.ingredients() != null) {
            for (RecipeProcessVisualizationInput.IngredientTarget ingredient : input.ingredients()) {
                String name = clean(ingredient.name());
                if (!name.isEmpty()) targets.add(name.toLowerCase(Locale.ROOT));
            }
        }
        if (input.subprocessNames() != null) {
            for (String name : input.subprocessNames()) {
                String cleaned = clean(name);
                if (!cleaned.isEmpty()) targets.add(cleaned);
            }
        }
        return targets.isEmpty() ? action : action + " " + joinNaturally(targets);
    }

    private static String joinNaturally(List<String> items) {
        if (items.size() == 1) return items.get(0);
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    private static String spokenTemperature(String temperature) {
        return temperature
                .replace("°C", " degrees Celsius")
                .replace("°F", " degrees Fahrenheit")
                .replace("°", " degrees")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String terminate(String sentence) {
        char last = sentence.charAt(sentence.length() - 1);
        return last == '.' || last == '!' || last == '?' ? sentence : sentence + ".";
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String text) {
        int max = Math.max(1, properties.getMaxScriptChars());
        if (text.length() <= max) {
            return text;
        }
        int cut = text.lastIndexOf(' ', max);
        return (cut > 0 ? text.substring(0, cut) : text.substring(0, max)).trim();
    }

    private static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((SCRIPT_VERSION + "|" + text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
