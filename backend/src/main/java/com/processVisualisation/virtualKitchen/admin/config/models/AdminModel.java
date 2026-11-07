package com.processVisualisation.virtualKitchen.admin.config.models;

import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelTier;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * An AI model added from the admin panel. It joins the models configured in application properties (it never
 * replaces one), and works without a restart for any provider the app already has a client for. Its enabled /
 * user-selectable / credits / fallback values are its starting point; the Models &amp; Credits page edits them like
 * any other model's.
 */
@Data
@Document(collection = "admin_ai_models")
public class AdminModel {

    /** The registry key. */
    @Id
    private String key;

    private AiCapability capability;
    private ModelTier tier;
    private String providerBean;
    private String providerModelId;
    private String displayName;
    private int creditCost;
    private String fallbackModelKey;
    private boolean enabled = true;
    private boolean userSelectable;

    /** Provider list prices for cost analytics (text); null leaves the cost unknown. */
    private Double inputUsdPerMillion;
    private Double outputUsdPerMillion;
    private Double cachedInputUsdPerMillion;
    /** Price per generated image (image models). */
    private Double perImageUsd;

    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ModelDefinition toDefinition() {
        ModelDefinition model = new ModelDefinition();
        model.setKey(key);
        model.setCapability(capability);
        model.setTier(tier);
        model.setProviderBean(providerBean);
        model.setProviderModelId(providerModelId);
        model.setDisplayName(displayName);
        model.setCreditCost(creditCost);
        model.setFallbackModelKey(fallbackModelKey);
        model.setEnabled(enabled);
        model.setUserSelectable(userSelectable);
        model.setInputUsdPerMillion(inputUsdPerMillion);
        model.setOutputUsdPerMillion(outputUsdPerMillion);
        model.setCachedInputUsdPerMillion(cachedInputUsdPerMillion);
        model.setAdminAdded(true);
        return model;
    }
}
