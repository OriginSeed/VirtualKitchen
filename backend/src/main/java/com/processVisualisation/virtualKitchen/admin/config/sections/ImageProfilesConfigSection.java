package com.processVisualisation.virtualKitchen.admin.config.sections;

import com.processVisualisation.virtualKitchen.admin.config.ConfigField;
import com.processVisualisation.virtualKitchen.admin.config.ConfigSection;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationImageProperties;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationImageProperties.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Image profiles: width, height and steps of the step images for each image purpose, plus the default profile.
 * Applied to {@link VisualizationImageProperties}, which {@code VisualizationImageProfiles} reads for every image.
 * Images already generated keep their size.
 */
@Component
@Order(40)
public class ImageProfilesConfigSection implements ConfigSection {

    private static final List<String> SIZES = Fields.values("", 256, 384, 512, 768, 1024);
    private static final List<String> STEPS = Fields.values("", 4, 8, 12, 20, 30);
    private static final String DEFAULT = "default";

    private static final Map<String, String> PURPOSES = new LinkedHashMap<>();

    static {
        PURPOSES.put(DEFAULT, "Default (any purpose not set below)");
        PURPOSES.put(VisualizationImageProperties.INGREDIENT_PREPARATION, "Ingredient preparation");
        PURPOSES.put(VisualizationImageProperties.INGREDIENT_ADDITION, "Ingredient addition");
        PURPOSES.put(VisualizationImageProperties.COOKING_PROCESS, "Cooking");
        PURPOSES.put(VisualizationImageProperties.MIXING, "Mixing");
        PURPOSES.put(VisualizationImageProperties.WAITING_RESTING, "Waiting & resting");
        PURPOSES.put(VisualizationImageProperties.SERVING, "Serving");
    }

    private final VisualizationImageProperties properties;
    private final Map<String, Profile> baseline = new LinkedHashMap<>();

    public ImageProfilesConfigSection(VisualizationImageProperties properties) {
        this.properties = properties;
        PURPOSES.keySet().forEach(purpose -> baseline.put(purpose, copy(profile(purpose))));
    }

    @Override
    public String id() {
        return "images";
    }

    @Override
    public String label() {
        return "Image Profiles";
    }

    @Override
    public String description() {
        return "Size and steps of new step images, per kind of step. Empty uses the default profile, then the "
                + "provider's own setting. Bigger images cost more time and money.";
    }

    @Override
    public List<ConfigField> fields(Map<String, String> overrides) {
        List<ConfigField> fields = new ArrayList<>();
        baseline.forEach((purpose, base) -> {
            String group = PURPOSES.get(purpose);
            fields.add(Fields.select(key(purpose, "width"), "Width (px)", group, SIZES, base.getWidth(), overrides, null));
            fields.add(Fields.select(key(purpose, "height"), "Height (px)", group, SIZES, base.getHeight(), overrides, null));
            fields.add(Fields.select(key(purpose, "steps"), "Steps", group, STEPS, base.getSteps(), overrides,
                    "Only providers with a steps setting use it."));
        });
        return fields;
    }

    @Override
    public void apply(Map<String, String> overrides) {
        Fields.requireKnown(overrides, fields(overrides));
        Map<String, Profile> next = new LinkedHashMap<>();
        baseline.forEach((purpose, base) -> {
            Profile profile = new Profile();
            profile.setWidth(Fields.optionalInt(overrides, key(purpose, "width"), base.getWidth()));
            profile.setHeight(Fields.optionalInt(overrides, key(purpose, "height"), base.getHeight()));
            profile.setSteps(Fields.optionalInt(overrides, key(purpose, "steps"), base.getSteps()));
            next.put(purpose, profile);
        });

        properties.setDefaultProfile(next.remove(DEFAULT));
        Map<String, Profile> profiles = new LinkedHashMap<>(properties.getProfiles());
        next.forEach((purpose, profile) -> {
            boolean empty = profile.getWidth() == null && profile.getHeight() == null && profile.getSteps() == null;
            if (empty) {
                profiles.remove(purpose);
            } else {
                profiles.put(purpose, profile);
            }
        });
        properties.setProfiles(profiles);
    }

    private Profile profile(String purpose) {
        if (DEFAULT.equals(purpose)) {
            return properties.getDefaultProfile();
        }
        return properties.getProfiles().get(purpose);
    }

    private static Profile copy(Profile profile) {
        Profile copy = new Profile();
        if (profile != null) {
            copy.setWidth(profile.getWidth());
            copy.setHeight(profile.getHeight());
            copy.setSteps(profile.getSteps());
        }
        return copy;
    }

    private static String key(String purpose, String property) {
        return "profile." + purpose + "." + property;
    }
}
