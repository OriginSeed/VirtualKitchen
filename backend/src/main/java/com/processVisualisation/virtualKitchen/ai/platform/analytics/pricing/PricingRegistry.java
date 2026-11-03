package com.processVisualisation.virtualKitchen.ai.platform.analytics.pricing;

import com.processVisualisation.virtualKitchen.ai.platform.dispatch.LoggingAiClient;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelProperties;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Versioned rate cards for every configured model, persisted in {@code ai_pricing_rates}.
 * <p>
 * The configured prices ({@code ai.models[n].*-usd-per-million}, plus {@code vk.ai.pricing.*}) are published once
 * per {@code vk.ai.pricing.version}: on first use, a model whose active card has a different version gets its card
 * closed and a new one opened. A changed price under an <em>unchanged</em> version is not applied silently: the
 * stored card stays in force and a warning asks for a version bump. Stored cards are never rewritten.
 */
@Component
public class PricingRegistry {

    private static final Logger log = LoggerFactory.getLogger(PricingRegistry.class);

    private final MongoTemplate mongoTemplate;
    private final AiModelProperties modelProperties;
    private final AnalyticsPricingProperties pricingProperties;
    private final Clock clock;

    private final Map<String, List<PricingRateCard>> cardsByModel = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    @Autowired
    public PricingRegistry(MongoTemplate mongoTemplate, AiModelProperties modelProperties,
                           AnalyticsPricingProperties pricingProperties) {
        this(mongoTemplate, modelProperties, pricingProperties, Clock.systemUTC());
    }

    PricingRegistry(MongoTemplate mongoTemplate, AiModelProperties modelProperties,
                    AnalyticsPricingProperties pricingProperties, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.modelProperties = modelProperties;
        this.pricingProperties = pricingProperties;
        this.clock = clock;
    }

    /** The card that priced {@code modelKey} at {@code at}, or empty when the model was never priced. */
    public Optional<PricingRateCard> rateFor(String modelKey, Instant at) {
        if (modelKey == null) {
            return Optional.empty();
        }
        ensureLoaded();
        List<PricingRateCard> cards = cardsByModel.getOrDefault(modelKey, List.of());
        return cards.stream().filter(card -> card.coversInstant(at)).findFirst();
    }

    /** Every stored card, newest first per model; for the admin pricing view. */
    public List<PricingRateCard> allCards() {
        ensureLoaded();
        List<PricingRateCard> all = new ArrayList<>();
        cardsByModel.values().forEach(all::addAll);
        all.sort(Comparator.comparing(PricingRateCard::getModelKey)
                .thenComparing(PricingRateCard::getEffectiveFrom, Comparator.nullsFirst(Comparator.reverseOrder())));
        return all;
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            publishConfiguredCards();
            reload();
            loaded = true;
        }
    }

    private void publishConfiguredCards() {
        Instant now = clock.instant();
        for (ModelDefinition model : modelProperties.getModels()) {
            PricingRateCard desired = cardFor(model, pricingProperties, now);
            PricingRateCard active = mongoTemplate.findOne(
                    Query.query(where("modelKey").is(model.getKey()).and("active").is(true)), PricingRateCard.class);
            switch (reconcile(active, desired)) {
                case INSERT -> mongoTemplate.save(desired);
                case SUPERSEDE -> {
                    active.setActive(false);
                    active.setEffectiveTo(now);
                    mongoTemplate.save(active);
                    mongoTemplate.save(desired);
                    log.info("event=ai_pricing_version_published modelKey={} previousVersion={} version={}",
                            model.getKey(), active.getVersion(), desired.getVersion());
                }
                case DRIFT -> log.warn("event=ai_pricing_drift modelKey={} version={} action=kept_stored_card "
                                + "hint=\"bump vk.ai.pricing.version to apply the changed prices\"",
                        model.getKey(), active.getVersion());
                case KEEP -> { }
            }
        }
    }

    /**
     * Prices a model added (or re-priced) from the admin panel. Configured models are priced from application
     * properties at startup; admin-added ones get their card here. Unchanged prices keep the active card; changed ones
     * close it and open a new one (version {@code admin-<epoch millis>}), so stored cards are never rewritten.
     *
     * @param perImageUsd the model's price per image, or null (text models price by tokens on {@code model})
     */
    public synchronized void publishAdminCard(ModelDefinition model, Double perImageUsd) {
        ensureLoaded();
        Instant now = clock.instant();
        PricingRateCard desired = cardFor(model, pricingProperties, now);
        String version = "admin-" + now.toEpochMilli();
        desired.setVersion(version);
        desired.setId(model.getKey() + "@" + version);
        desired.setPerImage(perImageUsd);
        desired.setSource("admin panel");
        PricingRateCard active = mongoTemplate.findOne(
                Query.query(where("modelKey").is(model.getKey()).and("active").is(true)), PricingRateCard.class);
        if (active != null && active.samePrices(desired)) {
            return;
        }
        if (active != null) {
            active.setActive(false);
            active.setEffectiveTo(now);
            mongoTemplate.save(active);
        }
        mongoTemplate.save(desired);
        log.info("event=ai_pricing_admin_card_published modelKey={} version={}", model.getKey(), version);
        reload();
    }

    private void reload() {
        cardsByModel.clear();
        for (PricingRateCard card : mongoTemplate.find(
                new Query().with(Sort.by(Sort.Direction.DESC, "effectiveFrom")), PricingRateCard.class)) {
            cardsByModel.computeIfAbsent(card.getModelKey(), k -> new ArrayList<>()).add(card);
        }
    }

    enum Reconciliation { INSERT, SUPERSEDE, DRIFT, KEEP }

    /** What to do with the configured card given the model's currently active stored card. */
    static Reconciliation reconcile(PricingRateCard active, PricingRateCard desired) {
        if (active == null) {
            return Reconciliation.INSERT;
        }
        if (!active.getVersion().equals(desired.getVersion())) {
            return active.samePrices(desired) ? Reconciliation.KEEP : Reconciliation.SUPERSEDE;
        }
        return active.samePrices(desired) ? Reconciliation.KEEP : Reconciliation.DRIFT;
    }

    /** The card the current configuration describes for {@code model}. */
    static PricingRateCard cardFor(ModelDefinition model, AnalyticsPricingProperties pricing, Instant now) {
        PricingRateCard card = new PricingRateCard();
        card.setVersion(pricing.getVersion());
        card.setId(model.getKey() + "@" + pricing.getVersion());
        card.setModelKey(model.getKey());
        card.setProvider(LoggingAiClient.providerOf(model.getProviderBean()));
        card.setProviderModelId(model.getProviderModelId());
        card.setModality(model.getCapability() == null ? null : model.getCapability().name());
        card.setTier(model.getTier() == null ? null : model.getTier().name());
        card.setInputPerMTok(model.getInputUsdPerMillion());
        card.setCachedInputPerMTok(model.getCachedInputUsdPerMillion());
        card.setOutputPerMTok(model.getOutputUsdPerMillion());
        card.setPerImage(pricing.getPerImageUsd().get(model.getKey()));
        card.setPerAudioSecond(pricing.getPerAudioSecondUsd().get(model.getKey()));
        card.setInfraPerComputeSecond(pricing.getLocalInfraUsdPerSecond().get(model.getKey()));
        card.setCurrency(pricing.getCurrency());
        card.setEffectiveFrom(now);
        card.setActive(true);
        card.setSource("application.properties ai.models[" + model.getKey() + "] + vk.ai.pricing");
        card.setCreatedAt(now);
        return card;
    }
}
