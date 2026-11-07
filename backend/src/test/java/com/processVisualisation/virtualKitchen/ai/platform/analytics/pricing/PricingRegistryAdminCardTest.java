package com.processVisualisation.virtualKitchen.ai.platform.analytics.pricing;

import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelProperties;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Admin-added models get their own rate cards; a re-price closes the old card, never rewrites it. */
class PricingRegistryAdminCardTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    private MongoTemplate mongoTemplate;
    private PricingRegistry registry;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        when(mongoTemplate.find(any(Query.class), eq(PricingRateCard.class))).thenReturn(List.of());
        registry = new PricingRegistry(mongoTemplate, new AiModelProperties(), new AnalyticsPricingProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aNewModelGetsAnActiveAdminCard() {
        registry.publishAdminCard(model(0.2, 0.6), null);

        ArgumentCaptor<PricingRateCard> saved = ArgumentCaptor.forClass(PricingRateCard.class);
        verify(mongoTemplate).save(saved.capture());
        PricingRateCard card = saved.getValue();
        assertEquals("admin-" + NOW.toEpochMilli(), card.getVersion());
        assertEquals("qwen3@admin-" + NOW.toEpochMilli(), card.getId());
        assertEquals(0.2, card.getInputPerMTok());
        assertTrue(card.isActive());
    }

    @Test
    void aRepriceClosesTheActiveCardAndOpensANewOne() {
        PricingRateCard active = new PricingRateCard();
        active.setModelKey("qwen3");
        active.setVersion("admin-1");
        active.setInputPerMTok(0.1);
        active.setActive(true);
        when(mongoTemplate.findOne(any(Query.class), eq(PricingRateCard.class))).thenReturn(active);

        registry.publishAdminCard(model(0.2, 0.6), null);

        assertFalse(active.isActive());
        assertEquals(NOW, active.getEffectiveTo());
        verify(mongoTemplate, times(2)).save(any(PricingRateCard.class));
    }

    @Test
    void unchangedPricesKeepTheActiveCard() {
        PricingRateCard active = PricingRegistry.cardFor(model(0.2, 0.6), new AnalyticsPricingProperties(), NOW);
        when(mongoTemplate.findOne(any(Query.class), eq(PricingRateCard.class))).thenReturn(active);

        registry.publishAdminCard(model(0.2, 0.6), null);

        verify(mongoTemplate, never()).save(any(PricingRateCard.class));
    }

    private static ModelDefinition model(double input, double output) {
        ModelDefinition model = new ModelDefinition();
        model.setKey("qwen3");
        model.setCapability(AiCapability.TEXT_TO_TEXT);
        model.setTier(ModelTier.PAID);
        model.setProviderBean("deepInfraAiClient");
        model.setProviderModelId("Qwen/Qwen3-235B");
        model.setInputUsdPerMillion(input);
        model.setOutputUsdPerMillion(output);
        return model;
    }
}
