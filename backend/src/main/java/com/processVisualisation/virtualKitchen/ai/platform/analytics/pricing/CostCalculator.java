package com.processVisualisation.virtualKitchen.ai.platform.analytics.pricing;

import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AiRequestAnalytics.Cost;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AiRequestAnalytics.Usage;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.CostBasis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Prices one provider call from its reported usage and the rate card in force when it ran. Pure and stateless.
 * <ul>
 *     <li><b>Tokens</b> (text, and Gemini image/audio, which bill image/audio output tokens):
 *     {@code (input - cached) x in + cached x cachedIn + output x out + reasoning x (reasoning ?: out)}, per 1M.</li>
 *     <li><b>Per image / per audio second</b>, for providers that report no tokens but have such a rate.</li>
 *     <li><b>Local engines</b>: no provider bill, so the direct cost is a calculated 0. Compute cost is estimated
 *     from the call duration only when {@code infraPerComputeSecond} is configured; otherwise it is unavailable.</li>
 * </ul>
 * Missing usage or a missing rate gives {@link CostBasis#UNAVAILABLE} with a null total — never zero.
 */
public final class CostCalculator {

    private static final double PER_MILLION = 1_000_000d;

    private CostCalculator() {
    }

    /**
     * @param usage      what the provider reported for the call (may be null)
     * @param card       the rate card in force when the call ran, or null when the model has none
     * @param durationMs the call's wall-clock duration, used only for local compute estimates
     * @param succeeded  whether the call returned a result (a failed call with no usage has an unknown charge)
     */
    public static Cost calculate(Usage usage, PricingRateCard card, Long durationMs, boolean succeeded, Instant now) {
        Cost cost = new Cost();
        cost.setCalculatedAt(now);
        if (card == null) {
            return unavailable(cost, "No rate card for this model");
        }
        cost.setCurrency(card.getCurrency());
        cost.setPricingRateId(card.getId());
        cost.setPricingVersion(card.getVersion());

        if (card.isLocal()) {
            return local(cost, card, durationMs);
        }

        boolean hasTokens = usage != null && (usage.getInputTokens() != null || usage.getOutputTokens() != null);
        boolean hasTokenPrices = card.getInputPerMTok() != null && card.getOutputPerMTok() != null;
        // An image model priced per image (e.g. one added from the admin panel) still reports tokens (gpt-image-*):
        // without token prices it is priced per image rather than left unpriced.
        if (hasTokens && (hasTokenPrices || card.getPerImage() == null || usage.getImageCount() == null)) {
            return tokens(cost, card, usage);
        }
        if (usage != null && card.getPerImage() != null && usage.getImageCount() != null) {
            double modality = usage.getImageCount() * card.getPerImage();
            return direct(cost, null, modality, String.format(Locale.ROOT, "%d image(s) x %s = %s",
                    usage.getImageCount(), money(card.getPerImage()), money(modality)));
        }
        if (usage != null && card.getPerAudioSecond() != null && usage.getAudioDurationMs() != null) {
            double seconds = usage.getAudioDurationMs() / 1000d;
            double modality = seconds * card.getPerAudioSecond();
            return direct(cost, null, modality, String.format(Locale.ROOT, "%.1fs audio x %s/s = %s",
                    seconds, money(card.getPerAudioSecond()), money(modality)));
        }
        return unavailable(cost, succeeded
                ? "Provider reported no usage to price"
                : "Failed call: the provider reported no usage, so any charge is unknown");
    }

    private static Cost tokens(Cost cost, PricingRateCard card, Usage usage) {
        if (card.getInputPerMTok() == null || card.getOutputPerMTok() == null) {
            return unavailable(cost, "Rate card has no token prices");
        }
        long input = orZero(usage.getInputTokens());
        long cached = Math.min(orZero(usage.getCachedInputTokens()), input);
        long uncached = input - cached;
        long output = orZero(usage.getOutputTokens());
        long reasoning = orZero(usage.getReasoningTokens());
        double cachedRate = card.getCachedInputPerMTok() != null ? card.getCachedInputPerMTok() : card.getInputPerMTok();
        double reasoningRate = card.getReasoningPerMTok() != null ? card.getReasoningPerMTok() : card.getOutputPerMTok();

        cost.setInputCost(uncached * card.getInputPerMTok() / PER_MILLION);
        cost.setCachedInputCost(cached * cachedRate / PER_MILLION);
        cost.setOutputCost(output * card.getOutputPerMTok() / PER_MILLION);
        cost.setReasoningCost(reasoning * reasoningRate / PER_MILLION);
        double total = cost.getInputCost() + cost.getCachedInputCost() + cost.getOutputCost() + cost.getReasoningCost();

        List<String> terms = new ArrayList<>();
        terms.add(term(uncached, "input", card.getInputPerMTok()));
        if (cached > 0) terms.add(term(cached, "cached input", cachedRate));
        terms.add(term(output, "output", card.getOutputPerMTok()));
        if (reasoning > 0) terms.add(term(reasoning, "reasoning", reasoningRate));
        String formula = String.join(" + ", terms) + " = " + money(total);
        if (usage.getInputTokens() == null || usage.getOutputTokens() == null) {
            formula += " (provider omitted " + (usage.getInputTokens() == null ? "input" : "output") + " tokens; counted as 0)";
        }
        cost.setDirectCost(total);
        cost.setTotalCost(total);
        cost.setCostBasis(CostBasis.CALCULATED);
        cost.setInfraCostBasis(CostBasis.UNAVAILABLE);
        cost.setFormula(formula);
        return cost;
    }

    private static Cost local(Cost cost, PricingRateCard card, Long durationMs) {
        cost.setDirectCost(0d);
        cost.setCostBasis(CostBasis.CALCULATED);
        String formula = "Local engine: no provider charge";
        if (card.getInfraPerComputeSecond() != null && durationMs != null) {
            double seconds = durationMs / 1000d;
            double infra = seconds * card.getInfraPerComputeSecond();
            cost.setInfraCost(infra);
            cost.setInfraCostBasis(CostBasis.ESTIMATED);
            cost.setTotalCost(infra);
            formula += String.format(Locale.ROOT, "; estimated compute %.1fs x %s/s = %s",
                    seconds, money(card.getInfraPerComputeSecond()), money(infra));
        } else {
            cost.setInfraCostBasis(CostBasis.UNAVAILABLE);
            cost.setTotalCost(0d);
            formula += "; compute cost not estimated (no vk.ai.pricing.local-infra-usd-per-second rate)";
        }
        cost.setFormula(formula);
        return cost;
    }

    private static Cost direct(Cost cost, Double tokenPart, double modality, String formula) {
        cost.setModalityCost(modality);
        double total = modality + (tokenPart == null ? 0 : tokenPart);
        cost.setDirectCost(total);
        cost.setTotalCost(total);
        cost.setCostBasis(CostBasis.CALCULATED);
        cost.setInfraCostBasis(CostBasis.UNAVAILABLE);
        cost.setFormula(formula);
        return cost;
    }

    private static Cost unavailable(Cost cost, String reason) {
        cost.setCostBasis(CostBasis.UNAVAILABLE);
        cost.setInfraCostBasis(CostBasis.UNAVAILABLE);
        cost.setFormula(reason);
        return cost;
    }

    private static String term(long tokens, String label, double ratePerMillion) {
        return String.format(Locale.ROOT, "%,d %s x %s/M", tokens, label, money(ratePerMillion));
    }

    static String money(double value) {
        return value != 0 && Math.abs(value) < 0.01
                ? String.format(Locale.ROOT, "$%.6f", value)
                : String.format(Locale.ROOT, "$%.4f", value);
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }
}
