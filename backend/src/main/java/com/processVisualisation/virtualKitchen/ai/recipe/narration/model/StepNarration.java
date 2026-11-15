package com.processVisualisation.virtualKitchen.ai.recipe.narration.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * The generated narration audio for one Process STEP, kept beside the recipe rather than inside
 * the step's node data so the editor's save, undo and crash-recovery paths never have to know
 * narration exists.
 * <p>
 * The narrated text itself is never stored: {@link #sourceTextHash} fingerprints the script the
 * audio was generated from, and the service compares it with a fresh hash of the step's current,
 * saved content on every read. A mismatch means the step was edited and the audio is stale.
 * <p>
 * {@link #generationToken} + {@link #leaseUntil} form the duplicate-generation guard: only the
 * request that claimed the token may commit audio, and an expired lease (a crashed generation)
 * can be taken over by the next request.
 */
@Data
@Document(collection = "step_narration")
public class StepNarration {

    @Id
    private String id;

    /** {@code recipeId::stepId} — one narration per step; the unique index is also the insert race guard. */
    @Indexed(unique = true)
    private String narrationKey;

    private Long recipeId;

    @Indexed
    private Long processId;

    private String stepId;

    private StepNarrationStatus status;

    /** SHA-256 of the versioned, normalized narration script this record was (or is being) generated from. */
    private String sourceTextHash;

    /** Indexed: an imported recipe file names its narration by this URL. */
    @Indexed
    private String audioUrl;
    private String storageBackend;
    private String storagePath;
    private String mimeType;
    private String format;
    private Long durationMs;
    private Long sizeBytes;

    private String provider;
    private String modelKey;
    private String providerModelId;
    private String voice;
    private String languageCode;

    private String generationToken;
    private Instant leaseUntil;
    private Long generatedByUserId;

    private String failureReason;
    private Instant failedAt;
    private int attempts;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant generatedAt;
}
