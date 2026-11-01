package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds {@code ai.visualization.image.*}: which image purpose each recipe step action has, and the generation
 * profile (size, inference steps) each purpose uses. The single place step images are classified — see
 * {@link VisualizationImageProfiles}.
 * <p>
 * Purposes come from the action's catalog {@code category} ({@link #purposeByCategory}), overridable per action
 * ({@link #purposeByAction}); both maps ship with defaults that configured entries are merged over. A purpose with
 * no profile, an unknown action and {@code custom} all use {@link #defaultProfile}. Profiles are empty unless
 * configured, in which case every provider keeps its own configured settings.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.visualization.image")
public class VisualizationImageProperties {

    public static final String INGREDIENT_PREPARATION = "ingredient-preparation";
    public static final String INGREDIENT_ADDITION = "ingredient-addition";
    public static final String COOKING_PROCESS = "cooking-process";
    public static final String MIXING = "mixing";
    public static final String WAITING_RESTING = "waiting-resting";
    public static final String SERVING = "serving";

    /** Purpose per step-catalog action category (e.g. {@code cutting -> ingredient-preparation}). */
    private Map<String, String> purposeByCategory = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("cleaning", INGREDIENT_PREPARATION),
            Map.entry("cutting", INGREDIENT_PREPARATION),
            Map.entry("coating", INGREDIENT_PREPARATION),
            Map.entry("dough", INGREDIENT_PREPARATION),
            Map.entry("handling", INGREDIENT_ADDITION),
            Map.entry("heating", COOKING_PROCESS),
            Map.entry("moist-heat", COOKING_PROCESS),
            Map.entry("frying", COOKING_PROCESS),
            Map.entry("dry-heat", COOKING_PROCESS),
            Map.entry("sauce", COOKING_PROCESS),
            Map.entry("mixing", MIXING),
            Map.entry("control", WAITING_RESTING),
            Map.entry("preservation", WAITING_RESTING),
            Map.entry("finishing", SERVING)));

    /** Purpose for single actions whose category doesn't fit them; wins over {@link #purposeByCategory}. */
    private Map<String, String> purposeByAction = new LinkedHashMap<>(Map.of(
            // Flipping happens in the pan, mid-cook, though the catalog files it under handling.
            "flip", COOKING_PROCESS));

    /** Used for actions without a purpose and purposes without a profile, and for any field a profile leaves unset. */
    private Profile defaultProfile = new Profile();

    /** Generation profile per purpose. */
    private Map<String, Profile> profiles = new LinkedHashMap<>();

    /** Generation settings for one purpose; a null field falls back to {@link #defaultProfile}, then the provider's own. */
    @Data
    public static class Profile {

        private Integer width;
        private Integer height;
        /** Only sent to providers whose API takes a step count (Draw Things); others ignore it. */
        private Integer steps;
    }
}
