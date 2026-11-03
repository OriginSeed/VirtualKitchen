package com.processVisualisation.virtualKitchen.admin.config.models;

import com.processVisualisation.virtualKitchen.admin.config.RuntimeConfigService;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.pricing.PricingRegistry;
import com.processVisualisation.virtualKitchen.ai.platform.dispatch.AiClientResolver;
import com.processVisualisation.virtualKitchen.ai.platform.preference.UserAiPreferences;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelRegistry;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelTier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Adds, edits and deletes AI models from the admin panel. A change is stored, then the Models &amp; Credits section is
 * applied again so the registry picks it up at once; if the registry rejects the result (a clashing key, a broken
 * fallback) the stored change is rolled back and the live registry is untouched.
 * <p>
 * Only providers the app already has a client for can be used; a new provider still needs code.
 */
@Service
public class AdminModelService {

    static final String MODELS_SECTION = "models";
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9-]{1,47}");
    private static final int MAX_TEXT = 200;

    /** Provider beans whose model is fixed by their own server, so a registry model id would be ignored. */
    static final Set<String> FIXED_MODEL_PROVIDERS = Set.of("drawThingsImageClient");

    /** Readable provider names; unknown beans show their bean name. */
    private static final Map<String, String> PROVIDER_LABELS = Map.of(
            "geminiAiClient", "Gemini",
            "openAiAiClient", "OpenAI",
            "ollamaAiClient", "Ollama (local)",
            "deepInfraAiClient", "DeepInfra",
            "geminiImageClient", "Gemini",
            "deepInfraImageClient", "DeepInfra",
            "geminiTtsProvider", "Gemini",
            "localTtsProvider", "Local TTS server");

    private final AdminModelRepository repository;
    private final AiModelRegistry registry;
    private final AiClientResolver clients;
    private final RuntimeConfigService configService;
    private final MongoTemplate mongoTemplate;
    private final PricingRegistry pricing;

    public AdminModelService(AdminModelRepository repository, AiModelRegistry registry, AiClientResolver clients,
                             RuntimeConfigService configService, MongoTemplate mongoTemplate, PricingRegistry pricing) {
        this.repository = repository;
        this.registry = registry;
        this.clients = clients;
        this.configService = configService;
        this.mongoTemplate = mongoTemplate;
        this.pricing = pricing;
    }

    /** What the "add model" form's dropdowns offer. */
    public Options options() {
        Map<AiCapability, List<Choice>> providers = new EnumMap<>(AiCapability.class);
        Map<AiCapability, List<Choice>> fallbacks = new EnumMap<>(AiCapability.class);
        for (AiCapability capability : AiCapability.values()) {
            providers.put(capability, providerBeans(capability).stream()
                    .map(bean -> new Choice(bean, PROVIDER_LABELS.getOrDefault(bean, bean)))
                    .toList());
            fallbacks.put(capability, registry.byCapability(capability).stream()
                    .map(model -> new Choice(model.getKey(), model.displayNameOrKey()))
                    .toList());
        }
        List<String> keys = registry.all().stream().map(ModelDefinition::getKey).toList();
        return new Options(Arrays.asList(AiCapability.values()), providers, Arrays.asList(ModelTier.values()),
                fallbacks, keys);
    }

    public List<AdminModel> list() {
        return repository.findAll();
    }

    /**
     * @throws IllegalArgumentException for an invalid or clashing model; nothing is stored then
     */
    public AdminModel add(ModelRequest request, Long adminId) {
        String key = trim(request.key());
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Key must be 2–48 lowercase letters, digits or dashes, starting with a letter or digit");
        }
        if (registry.find(key).isPresent() || repository.existsById(key)) {
            throw new IllegalArgumentException("A model with key '" + key + "' already exists");
        }
        AiCapability capability = require(request.capability(), "Capability");
        String providerBean = trim(request.providerBean());
        if (providerBean == null || !providerBeans(capability).contains(providerBean)) {
            throw new IllegalArgumentException("'" + providerBean + "' can't serve " + capability + " models");
        }
        ModelTier tier = require(request.tier(), "Tier");

        AdminModel model = new AdminModel();
        model.setKey(key);
        model.setCapability(capability);
        model.setProviderBean(providerBean);
        model.setTier(tier);
        model.setCreditCost(creditCost(tier, request.creditCost()));
        model.setFallbackModelKey(trim(request.fallbackModelKey()));
        model.setEnabled(request.enabled() == null || request.enabled());
        model.setUserSelectable(Boolean.TRUE.equals(request.userSelectable()));
        applyDetails(model, request);
        model.setCreatedBy(adminId);
        model.setCreatedAt(LocalDateTime.now());
        model.setUpdatedAt(model.getCreatedAt());

        repository.save(model);
        try {
            configService.reapply(MODELS_SECTION);
        } catch (RuntimeException e) {
            repository.deleteById(key);
            configService.reapply(MODELS_SECTION);
            throw e;
        }
        pricing.publishAdminCard(model.toDefinition(), model.getPerImageUsd());
        configService.audit(MODELS_SECTION, "MODEL_ADD", adminId, Map.of(), describe(model));
        return model;
    }

    /** Edits an added model's own details: display name, provider model id and prices. */
    public AdminModel update(String key, ModelRequest request, Long adminId) {
        AdminModel model = repository.findById(key)
                .orElseThrow(() -> new IllegalArgumentException("'" + key + "' is not a model added from the admin panel"));
        Map<String, String> before = describe(model);
        AdminModel previous = copyOf(model);
        applyDetails(model, request);
        model.setUpdatedAt(LocalDateTime.now());

        repository.save(model);
        try {
            configService.reapply(MODELS_SECTION);
        } catch (RuntimeException e) {
            repository.save(previous);
            configService.reapply(MODELS_SECTION);
            throw e;
        }
        pricing.publishAdminCard(model.toDefinition(), model.getPerImageUsd());
        configService.audit(MODELS_SECTION, "MODEL_EDIT", adminId, before, describe(model));
        return model;
    }

    /**
     * Deletes an added model.
     *
     * @throws IllegalArgumentException while it is a default, another model's fallback, or a user's saved choice
     */
    public void delete(String key, Long adminId) {
        AdminModel model = repository.findById(key)
                .orElseThrow(() -> new IllegalArgumentException("'" + key + "' is not a model added from the admin panel"));
        if (key.equals(registry.defaultKeyFor(model.getCapability()))) {
            throw new IllegalArgumentException("'" + key + "' is the default " + model.getCapability()
                    + " model; choose another default first");
        }
        Optional<ModelDefinition> dependent = registry.all().stream()
                .filter(other -> key.equals(other.getFallbackModelKey()))
                .findFirst();
        if (dependent.isPresent()) {
            throw new IllegalArgumentException("'" + dependent.get().getKey() + "' falls back to '" + key
                    + "'; change its fallback first");
        }
        long savedBy = mongoTemplate.count(query(where("modelKeys." + model.getCapability().name()).is(key)),
                UserAiPreferences.class);
        if (savedBy > 0) {
            throw new IllegalArgumentException(savedBy + " user(s) chose '" + key
                    + "'; disable it or stop offering it to users instead, so their requests move to the default");
        }

        String overridePrefix = "model." + key + ".";
        configService.dropOverrides(MODELS_SECTION, overrideKey -> overrideKey.startsWith(overridePrefix), adminId);
        repository.deleteById(key);
        configService.reapply(MODELS_SECTION);
        configService.audit(MODELS_SECTION, "MODEL_DELETE", adminId, describe(model), Map.of());
    }

    /** Provider beans that can serve {@code capability} with a model id of the admin's choosing. */
    public List<String> providerBeans(AiCapability capability) {
        return clients.providerBeans(capability).stream().filter(bean -> !FIXED_MODEL_PROVIDERS.contains(bean)).toList();
    }

    private static void applyDetails(AdminModel model, ModelRequest request) {
        String providerModelId = trim(request.providerModelId());
        if (providerModelId == null || providerModelId.length() > MAX_TEXT) {
            throw new IllegalArgumentException("Provider model id is required (at most " + MAX_TEXT + " characters)");
        }
        String displayName = trim(request.displayName());
        if (displayName == null || displayName.length() > MAX_TEXT) {
            throw new IllegalArgumentException("Display name is required (at most " + MAX_TEXT + " characters)");
        }
        model.setProviderModelId(providerModelId);
        model.setDisplayName(displayName);
        model.setInputUsdPerMillion(price(request.inputUsdPerMillion(), "Input price"));
        model.setOutputUsdPerMillion(price(request.outputUsdPerMillion(), "Output price"));
        model.setCachedInputUsdPerMillion(price(request.cachedInputUsdPerMillion(), "Cached input price"));
        model.setPerImageUsd(price(request.perImageUsd(), "Price per image"));
    }

    /** Open-source models never cost credits; a paid one costs at least one. */
    private static int creditCost(ModelTier tier, Integer requested) {
        if (tier == ModelTier.OPEN_SOURCE) {
            return 0;
        }
        int cost = requested == null ? 1 : requested;
        if (cost < 1 || cost > 10) {
            throw new IllegalArgumentException("A paid model costs 1–10 credits per request");
        }
        return cost;
    }

    private static Double price(Double value, String label) {
        if (value == null) {
            return null;
        }
        if (value < 0 || value > 10_000 || value.isNaN()) {
            throw new IllegalArgumentException(label + " must be between 0 and 10000 USD");
        }
        return value;
    }

    private static <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, String> describe(AdminModel model) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("key", model.getKey());
        values.put("capability", String.valueOf(model.getCapability()));
        values.put("provider", model.getProviderBean());
        values.put("providerModelId", model.getProviderModelId());
        values.put("displayName", model.getDisplayName());
        values.put("tier", String.valueOf(model.getTier()));
        values.put("creditCost", String.valueOf(model.getCreditCost()));
        return values;
    }

    private static AdminModel copyOf(AdminModel model) {
        AdminModel copy = new AdminModel();
        copy.setKey(model.getKey());
        copy.setCapability(model.getCapability());
        copy.setTier(model.getTier());
        copy.setProviderBean(model.getProviderBean());
        copy.setProviderModelId(model.getProviderModelId());
        copy.setDisplayName(model.getDisplayName());
        copy.setCreditCost(model.getCreditCost());
        copy.setFallbackModelKey(model.getFallbackModelKey());
        copy.setEnabled(model.isEnabled());
        copy.setUserSelectable(model.isUserSelectable());
        copy.setInputUsdPerMillion(model.getInputUsdPerMillion());
        copy.setOutputUsdPerMillion(model.getOutputUsdPerMillion());
        copy.setCachedInputUsdPerMillion(model.getCachedInputUsdPerMillion());
        copy.setPerImageUsd(model.getPerImageUsd());
        copy.setCreatedBy(model.getCreatedBy());
        copy.setCreatedAt(model.getCreatedAt());
        copy.setUpdatedAt(model.getUpdatedAt());
        return copy;
    }

    /** The add / edit form. On edit only the provider model id, display name and prices are used. */
    public record ModelRequest(String key, AiCapability capability, String providerBean, String providerModelId,
                               String displayName, ModelTier tier, Integer creditCost, String fallbackModelKey,
                               Boolean enabled, Boolean userSelectable, Double inputUsdPerMillion,
                               Double outputUsdPerMillion, Double cachedInputUsdPerMillion, Double perImageUsd) {
    }

    public record Choice(String value, String label) {
    }

    /**
     * @param providers per capability, the provider beans a new model can use
     * @param fallbacks per capability, the models a new one can fall back to
     * @param takenKeys every key in use, so the form can warn before saving
     */
    public record Options(List<AiCapability> capabilities, Map<AiCapability, List<Choice>> providers,
                          List<ModelTier> tiers, Map<AiCapability, List<Choice>> fallbacks, List<String> takenKeys) {
    }
}
