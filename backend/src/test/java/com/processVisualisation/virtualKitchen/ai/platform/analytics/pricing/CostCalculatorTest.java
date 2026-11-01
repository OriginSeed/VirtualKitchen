package com.processVisualisation.virtualKitchen.ai.platform.analytics.pricing;

import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AiRequestAnalytics.Cost;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AiRequestAnalytics.Usage;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.CostBasis;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelTier;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CostCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");

    static PricingRateCard paidText() {
        PricingRateCard card = new PricingRateCard();
        card.setId("gemini-flash@v1");
        card.setVersion("v1");
        card.setModelKey("gemini-flash");
        card.setTier("PAID");
        card.setInputPerMTok(0.25);
        card.setCachedInputPerMTok(0.025);
        card.setOutputPerMTok(1.50);
        card.setCurrency("USD");
        return card;
    }

    static PricingRateCard local() {
        PricingRateCard card = new PricingRateCard();
        card.setId("ollama-qwen@v1");
        card.setVersion("v1");
        card.setModelKey("ollama-qwen");
        card.setTier("OPEN_SOURCE");
        card.setInputPerMTok(0d);
        card.setOutputPerMTok(0d);
        card.setCurrency("USD");
        return card;
    }

    static Usage tokens(Long input, Long cached, Long output, Long reasoning) {
        Usage usage = new Usage();
        usage.setInputTokens(input);
        usage.setCachedInputTokens(cached);
        usage.setOutputTokens(output);
        usage.setReasoningTokens(reasoning);
        return usage;
    }

    @Test
    void deepInfraTokens_priceCachedAndUncachedInputSeparately() {
        PricingRateCard card = paidText();
        card.setInputPerMTok(0.20);
        card.setCachedInputPerMTok(0.006);
        card.setOutputPerMTok(0.60);

        Cost partlyCached = CostCalculator.calculate(tokens(12_000L, 10_240L, 800L, null), card, 900L, true, NOW);
        // 1,760 x $0.20/M + 10,240 x $0.006/M + 800 x $0.60/M
        assertThat(partlyCached.getInputCost()).isCloseTo(0.000352, within(1e-12));
        assertThat(partlyCached.getCachedInputCost()).isCloseTo(0.00006144, within(1e-12));
        assertThat(partlyCached.getOutputCost()).isCloseTo(0.00048, within(1e-12));
        assertThat(partlyCached.getTotalCost()).isCloseTo(0.00089344, within(1e-12));

        // No cache report: every input token is billed at the uncached rate, never assumed cached.
        Cost uncached = CostCalculator.calculate(tokens(12_000L, null, 800L, null), card, 900L, true, NOW);
        assertThat(uncached.getInputCost()).isCloseTo(0.0024, within(1e-12));
        assertThat(uncached.getCachedInputCost()).isZero();
        assertThat(uncached.getTotalCost()).isCloseTo(0.00288, within(1e-12));
    }

    @Test
    void textTokens_arePricedPerMillionWithTheCardRecorded() {
        Cost cost = CostCalculator.calculate(tokens(1_000_000L, null, 200_000L, null), paidText(), 1200L, true, NOW);

        assertThat(cost.getCostBasis()).isEqualTo(CostBasis.CALCULATED);
        assertThat(cost.getInputCost()).isCloseTo(0.25, within(1e-12));
        assertThat(cost.getOutputCost()).isCloseTo(0.30, within(1e-12));
        assertThat(cost.getTotalCost()).isCloseTo(0.55, within(1e-12));
        assertThat(cost.getPricingRateId()).isEqualTo("gemini-flash@v1");
        assertThat(cost.getPricingVersion()).isEqualTo("v1");
        assertThat(cost.getCurrency()).isEqualTo("USD");
        assertThat(cost.getFormula()).contains("1,000,000 input").contains("= $0.5500");
    }

    @Test
    void cachedTokens_useTheCachedRateAndReasoningUsesTheOutputRate() {
        // 1M input of which 400k cached; 100k output + 50k reasoning.
        Cost cost = CostCalculator.calculate(tokens(1_000_000L, 400_000L, 100_000L, 50_000L), paidText(), 1L, true, NOW);

        assertThat(cost.getInputCost()).isCloseTo(600_000 * 0.25 / 1e6, within(1e-12));
        assertThat(cost.getCachedInputCost()).isCloseTo(400_000 * 0.025 / 1e6, within(1e-12));
        assertThat(cost.getOutputCost()).isCloseTo(100_000 * 1.50 / 1e6, within(1e-12));
        assertThat(cost.getReasoningCost()).isCloseTo(50_000 * 1.50 / 1e6, within(1e-12));
        assertThat(cost.getTotalCost()).isCloseTo(0.15 + 0.01 + 0.15 + 0.075, within(1e-12));
    }

    @Test
    void imageTokens_arePricedLikeTextAtTheImageModelsRates() {
        PricingRateCard image = paidText();
        image.setId("gemini-image@v1");
        image.setInputPerMTok(0.3);
        image.setCachedInputPerMTok(null);
        image.setOutputPerMTok(30.0);
        Usage usage = tokens(20L, null, 1290L, null);
        usage.setImageCount(1);

        Cost cost = CostCalculator.calculate(usage, image, 4000L, true, NOW);

        assertThat(cost.getTotalCost()).isCloseTo((20 * 0.3 + 1290 * 30.0) / 1e6, within(1e-12));
        assertThat(cost.getTotalCost()).isCloseTo(0.039, within(0.0005));
    }

    @Test
    void perImageRate_appliesWhenTheProviderReportsNoTokens() {
        PricingRateCard card = paidText();
        card.setPerImage(0.04);
        Usage usage = new Usage();
        usage.setImageCount(2);

        Cost cost = CostCalculator.calculate(usage, card, 1000L, true, NOW);

        assertThat(cost.getCostBasis()).isEqualTo(CostBasis.CALCULATED);
        assertThat(cost.getModalityCost()).isCloseTo(0.08, within(1e-12));
        assertThat(cost.getTotalCost()).isCloseTo(0.08, within(1e-12));
    }

    @Test
    void audio_isPricedByTokensOrBySecondsWhenThatIsTheBillingUnit() {
        PricingRateCard tts = paidText();
        tts.setInputPerMTok(0.5);
        tts.setOutputPerMTok(10.0);
        tts.setCachedInputPerMTok(null);
        assertThat(CostCalculator.calculate(tokens(85L, null, 400L, null), tts, 1L, true, NOW).getTotalCost())
                .isCloseTo((85 * 0.5 + 400 * 10.0) / 1e6, within(1e-12));

        PricingRateCard perSecond = paidText();
        perSecond.setPerAudioSecond(0.001);
        Usage audio = new Usage();
        audio.setAudioDurationMs(12_500L);
        Cost cost = CostCalculator.calculate(audio, perSecond, 1L, true, NOW);
        assertThat(cost.getTotalCost()).isCloseTo(0.0125, within(1e-12));
    }

    @Test
    void localModel_hasZeroDirectCostAndUnavailableComputeCostByDefault() {
        Cost cost = CostCalculator.calculate(new Usage(), local(), 3000L, true, NOW);

        assertThat(cost.getCostBasis()).isEqualTo(CostBasis.CALCULATED);
        assertThat(cost.getDirectCost()).isZero();
        assertThat(cost.getInfraCost()).isNull();
        assertThat(cost.getInfraCostBasis()).isEqualTo(CostBasis.UNAVAILABLE);
        assertThat(cost.getFormula()).contains("compute cost not estimated");
    }

    @Test
    void localModel_estimatesComputeCostWhenARateIsConfigured() {
        PricingRateCard card = local();
        card.setInfraPerComputeSecond(0.0002);

        Cost cost = CostCalculator.calculate(new Usage(), card, 5000L, true, NOW);

        assertThat(cost.getDirectCost()).isZero();
        assertThat(cost.getInfraCost()).isCloseTo(0.001, within(1e-12));
        assertThat(cost.getInfraCostBasis()).isEqualTo(CostBasis.ESTIMATED);
        assertThat(cost.getTotalCost()).isCloseTo(0.001, within(1e-12));
    }

    @Test
    void unknownModel_isUnavailableNotZero() {
        Cost cost = CostCalculator.calculate(tokens(100L, null, 100L, null), null, 1L, true, NOW);

        assertThat(cost.getCostBasis()).isEqualTo(CostBasis.UNAVAILABLE);
        assertThat(cost.getTotalCost()).isNull();
    }

    @Test
    void missingUsage_isUnavailableNotZero() {
        Cost success = CostCalculator.calculate(new Usage(), paidText(), 1L, true, NOW);
        Cost failure = CostCalculator.calculate(null, paidText(), 1L, false, NOW);

        assertThat(success.getCostBasis()).isEqualTo(CostBasis.UNAVAILABLE);
        assertThat(success.getTotalCost()).isNull();
        assertThat(failure.getCostBasis()).isEqualTo(CostBasis.UNAVAILABLE);
        assertThat(failure.getFormula()).contains("any charge is unknown");
    }

    @Test
    void cardWithoutTokenPrices_isUnavailable() {
        PricingRateCard card = paidText();
        card.setInputPerMTok(null);

        assertThat(CostCalculator.calculate(tokens(1L, null, 1L, null), card, 1L, true, NOW).getCostBasis())
                .isEqualTo(CostBasis.UNAVAILABLE);
    }

    @Test
    void currency_comesFromTheCard() {
        PricingRateCard card = paidText();
        card.setCurrency("EUR");

        assertThat(CostCalculator.calculate(tokens(1L, null, 1L, null), card, 1L, true, NOW).getCurrency()).isEqualTo("EUR");
    }

    @Test
    void pricingVersions_areSelectedByEffectiveDate() {
        PricingRateCard v1 = paidText();
        v1.setEffectiveFrom(Instant.parse("2026-01-01T00:00:00Z"));
        v1.setEffectiveTo(Instant.parse("2026-06-01T00:00:00Z"));
        PricingRateCard v2 = paidText();
        v2.setId("gemini-flash@v2");
        v2.setVersion("v2");
        v2.setInputPerMTok(0.5);
        v2.setEffectiveFrom(Instant.parse("2026-06-01T00:00:00Z"));

        assertThat(v1.coversInstant(Instant.parse("2026-03-01T00:00:00Z"))).isTrue();
        assertThat(v1.coversInstant(Instant.parse("2026-07-01T00:00:00Z"))).isFalse();
        assertThat(v2.coversInstant(Instant.parse("2026-07-01T00:00:00Z"))).isTrue();
        assertThat(CostCalculator.calculate(tokens(1_000_000L, null, 0L, null), v2, 1L, true, NOW).getTotalCost())
                .isCloseTo(0.5, within(1e-12));
    }

    @Test
    void reconcile_publishesNewVersionsButNeverSilentlyRewritesAStoredCard() {
        ModelDefinition model = new ModelDefinition();
        model.setKey("gemini-flash");
        model.setCapability(AiCapability.TEXT_TO_TEXT);
        model.setTier(ModelTier.PAID);
        model.setProviderBean("geminiAiClient");
        model.setInputUsdPerMillion(0.25);
        model.setOutputUsdPerMillion(1.5);
        AnalyticsPricingProperties props = new AnalyticsPricingProperties();
        PricingRateCard stored = PricingRegistry.cardFor(model, props, NOW);

        assertThat(PricingRegistry.reconcile(null, stored)).isEqualTo(PricingRegistry.Reconciliation.INSERT);
        assertThat(PricingRegistry.reconcile(stored, PricingRegistry.cardFor(model, props, NOW)))
                .isEqualTo(PricingRegistry.Reconciliation.KEEP);

        model.setInputUsdPerMillion(0.30);
        assertThat(PricingRegistry.reconcile(stored, PricingRegistry.cardFor(model, props, NOW)))
                .as("changed price, same version: kept, with a warning")
                .isEqualTo(PricingRegistry.Reconciliation.DRIFT);

        props.setVersion("v2");
        PricingRateCard v2 = PricingRegistry.cardFor(model, props, NOW);
        assertThat(PricingRegistry.reconcile(stored, v2)).isEqualTo(PricingRegistry.Reconciliation.SUPERSEDE);
        assertThat(v2.getId()).isEqualTo("gemini-flash@v2");
        assertThat(v2.getProvider()).isEqualTo("gemini");
    }
}
