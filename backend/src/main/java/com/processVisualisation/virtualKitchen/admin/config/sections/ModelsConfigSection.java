package com.processVisualisation.virtualKitchen.admin.config.sections;

import com.processVisualisation.virtualKitchen.admin.config.ConfigField;
import com.processVisualisation.virtualKitchen.admin.config.ConfigSection;
import com.processVisualisation.virtualKitchen.admin.config.models.AdminModel;
import com.processVisualisation.virtualKitchen.admin.config.models.AdminModelRepository;
import com.processVisualisation.virtualKitchen.ai.platform.credit.AiCreditProperties;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelRegistry;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Models &amp; credits: per model enabled / user-selectable / credit cost / fallback, the default model per
 * capability, and the monthly credit allocation. Applied to {@link AiModelRegistry} and {@link AiCreditProperties}.
 * <p>
 * Covers the configured models and the ones added from the admin panel ({@link AdminModel}); an added model's stored
 * values are its baseline here, so "reset" brings it back to how it was added.
 */
@Component
@Order(10)
public class ModelsConfigSection implements ConfigSection {

    static final String DEFAULT_PREFIX = "default.";
    static final String MODEL_PREFIX = "model.";
    static final String ALLOCATION = "credits.monthlyAllocation";

    private static final List<String> CREDIT_COSTS = IntStream.rangeClosed(0, 10).mapToObj(String::valueOf).toList();
    private static final List<String> ALLOCATIONS = List.of("0", "50", "100", "200", "300", "500", "1000");

    private final AiModelRegistry registry;
    private final AiCreditProperties creditProperties;
    private final AdminModelRepository adminModels;
    private final int baselineAllocation;

    public ModelsConfigSection(AiModelRegistry registry, AiCreditProperties creditProperties,
                               AdminModelRepository adminModels) {
        this.registry = registry;
        this.creditProperties = creditProperties;
        this.adminModels = adminModels;
        this.baselineAllocation = creditProperties.getDefaultMonthlyAllocation();
    }

    /** Configured models, then admin-added ones (in the order they were added). */
    private List<ModelDefinition> allModels(List<ModelDefinition> added) {
        List<ModelDefinition> all = new ArrayList<>(registry.baselineModels());
        all.addAll(added);
        return all;
    }

    private List<ModelDefinition> addedModels() {
        return adminModels.findAll().stream()
                .sorted(Comparator.comparing(AdminModel::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(AdminModel::toDefinition)
                .toList();
    }

    /** Admin-added models live outside the overrides, so they must be applied at startup even without any. */
    @Override
    public boolean applyAtStartup() {
        return adminModels.count() > 0;
    }

    @Override
    public String id() {
        return "models";
    }

    @Override
    public String label() {
        return "Models & Credits";
    }

    @Override
    public String description() {
        return "Which models run by default, which users may choose, what each request costs in credits, and what a "
                + "model falls back to when the user is out of credits.";
    }

    @Override
    public List<ConfigField> fields(Map<String, String> overrides) {
        List<ModelDefinition> baseline = allModels(addedModels());
        Map<String, String> baselineDefaults = registry.baselineDefaults();
        List<ConfigField> fields = new ArrayList<>();

        for (AiCapability capability : AiCapability.values()) {
            List<String> keys = baseline.stream().filter(m -> m.getCapability() == capability)
                    .map(ModelDefinition::getKey).toList();
            if (keys.isEmpty()) {
                continue;
            }
            String key = DEFAULT_PREFIX + capability.name();
            String base = baselineDefaults.getOrDefault(capability.name(), "");
            fields.add(ConfigField.select(key, "Default " + capabilityLabel(capability) + " model", "Defaults",
                    keys, base, overrides.getOrDefault(key, base), overrides.containsKey(key),
                    "Used when the user has no preference of their own."));
        }
        String allocation = String.valueOf(baselineAllocation);
        fields.add(ConfigField.select(ALLOCATION, "Monthly credit allocation", "Defaults", ALLOCATIONS,
                allocation, overrides.getOrDefault(ALLOCATION, allocation), overrides.containsKey(ALLOCATION),
                "Credits each user gets per monthly cycle (new accounts and the next renewal)."));

        for (ModelDefinition model : baseline) {
            String group = model.displayNameOrKey() + " · " + capabilityLabel(model.getCapability())
                    + " · " + model.getTier() + (model.isAdminAdded() ? " · added" : "");
            String prefix = MODEL_PREFIX + model.getKey() + ".";
            fields.add(Fields.toggle(prefix + "enabled", "Enabled", group, model.isEnabled(), overrides,
                    "A disabled model is never selected."));
            fields.add(Fields.toggle(prefix + "userSelectable", "Users can choose it", group, model.isUserSelectable(),
                    overrides, "Offered in the users' model picker."));
            fields.add(Fields.select(prefix + "creditCost", "Credits per request", group, CREDIT_COSTS,
                    String.valueOf(model.getCreditCost()), overrides, "Deducted for each successful request."));
            List<String> fallbacks = new ArrayList<>();
            fallbacks.add("");
            baseline.stream()
                    .filter(m -> m.getCapability() == model.getCapability() && !m.getKey().equals(model.getKey()))
                    .forEach(m -> fallbacks.add(m.getKey()));
            fields.add(Fields.select(prefix + "fallback", "Fallback when out of credits", group, fallbacks,
                    model.getFallbackModelKey() == null ? "" : model.getFallbackModelKey(), overrides,
                    "Empty means the request fails when the user can't pay."));
        }
        return fields;
    }

    @Override
    public void apply(Map<String, String> overrides) {
        Map<String, Consumer<ModelDefinition>> edits = new LinkedHashMap<>();
        Map<String, String> defaults = new LinkedHashMap<>();
        Integer allocation = null;

        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key.equals(ALLOCATION)) {
                allocation = Integer.parseInt(value);
            } else if (key.startsWith(DEFAULT_PREFIX)) {
                defaults.put(key.substring(DEFAULT_PREFIX.length()), value);
            } else if (key.startsWith(MODEL_PREFIX)) {
                int dot = key.lastIndexOf('.');
                String modelKey = key.substring(MODEL_PREFIX.length(), dot);
                Consumer<ModelDefinition> edit = modelEdit(key.substring(dot + 1), value);
                edits.merge(modelKey, edit, Consumer::andThen);
            } else {
                throw new IllegalArgumentException("Unknown models setting '" + key + "'");
            }
        }

        registry.apply(addedModels(), edits, defaults);
        creditProperties.setDefaultMonthlyAllocation(allocation != null ? allocation : baselineAllocation);
    }

    private static Consumer<ModelDefinition> modelEdit(String property, String value) {
        return switch (property) {
            case "enabled" -> m -> m.setEnabled(Boolean.parseBoolean(value));
            case "userSelectable" -> m -> m.setUserSelectable(Boolean.parseBoolean(value));
            case "creditCost" -> {
                int cost = Integer.parseInt(value);
                yield m -> m.setCreditCost(cost);
            }
            case "fallback" -> m -> m.setFallbackModelKey(value.isBlank() ? null : value);
            default -> throw new IllegalArgumentException("Unknown model setting '" + property + "'");
        };
    }

    static String capabilityLabel(AiCapability capability) {
        return switch (capability) {
            case TEXT_TO_TEXT -> "text";
            case TEXT_TO_IMAGE -> "image";
            case TEXT_TO_SPEECH -> "speech";
        };
    }
}
