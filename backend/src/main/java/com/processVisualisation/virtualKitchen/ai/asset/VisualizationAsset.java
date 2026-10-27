package com.processVisualisation.virtualKitchen.ai.asset;

import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelTier;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * MongoDB document representing a single AI-generated visualization asset
 * (an image and/or video, with the prompts used to produce them) stored in
 * the {@code visualization_asset} collection.
 */
@Data
@Document(collection = "visualization_asset")
public class VisualizationAsset {

    public static final String SEQUENCE_NAME = "visualization_asset_sequence";

    @Id
    private Long id;

    @Indexed(unique = true)
    private String visualizationKey;

    private VisualizationAssetType type;

    private String imagePrompt;

    /**
     * Scene anchor only ({@code recipeId::scene}): one line naming the dish ("Part of making Chicken Curry, a North
     * Indian home-style dish"), sent with every step image of the recipe. Null on every other asset.
     */
    private String dishContext;

    private String imageUrl;

    /**
     * Why {@link #imageUrl} is null, when it is — the generation or upload failure's
     * real cause, rather than the generic "image generation failed" the job layer used
     * to infer from a null URL alone. Null whenever {@link #imageUrl} is populated.
     */
    private String imageFailureReason;

    private String videoPrompt;

    private String videoUrl;

    /** Registry key of the model that produced {@link #imageUrl} (the image-generation call, not the prompt-authoring one). */
    private String resolvedModelKey;

    private ModelTier resolvedTier;

    /** Whether {@link #resolvedModelKey} was a fallback because premium credits were exhausted. */
    private boolean usedFallback;

    // --- Global asset fields: only set when type == GLOBAL; null for step visualizations ---

    /** Which catalog the global resource belongs to. */
    private GlobalResourceType resourceType;

    /** Id of the resource within its catalog (e.g. the store {@code Ingredient} id). */
    private Long resourceId;

    /** Lifecycle of the global image generation; step visualizations leave this null. */
    private GlobalAssetStatus generationStatus;

    /** Identifies the generation that currently owns this asset; stale generations cannot overwrite it. */
    private String generationToken;

    /** Until when the owning generation's claim blocks a new one; past it, a stuck generation can be re-claimed. */
    private Instant leaseUntil;

    /** The admin who requested the current generation (also the user charged for it). */
    private Long requestedBy;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
