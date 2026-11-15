package com.processVisualisation.virtualKitchen.ai.recipe.narration;

import com.processVisualisation.virtualKitchen.ai.recipe.narration.model.StepNarration;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Every write to {@code step_narration} goes through this interface. Each operation is a single
 * atomic step, which is what makes the duplicate-generation guard hold across concurrent
 * requests and multiple application instances.
 */
public interface StepNarrationStore {

    Optional<StepNarration> find(String narrationKey);

    List<StepNarration> findAll(Collection<String> narrationKeys);

    /**
     * Atomically claims the right to generate narration for {@code claim.sourceTextHash()}.
     * <p>
     * The claim is refused, and empty is returned, while another generation for the same hash
     * still holds an unexpired lease. Without {@code force}, it is also refused when READY audio
     * for that hash already exists. A claim for a different hash supersedes any older claim, so
     * the newest text always wins.
     *
     * @return the claimed record (status GENERATING, carrying {@code claim.token()}), or empty if refused
     */
    Optional<StepNarration> claim(Claim claim);

    /**
     * Marks the narration READY with the stored audio, but only while it still carries {@code token}.
     *
     * @return the record as it was before the update, or empty if the claim had been superseded
     */
    Optional<StepNarration> commitReady(String narrationKey, String token, ReadyAudio audio);

    /** Marks the narration FAILED, but only while it still carries {@code token}. */
    boolean markFailed(String narrationKey, String token, String reason, Instant failedAt);

    /** Every READY narration of the recipe, whatever process it belongs to. */
    List<StepNarration> findReadyByRecipeId(Long recipeId);

    /** The READY narration whose audio is at {@code audioUrl}, if there still is one. */
    Optional<StepNarration> findReadyByAudioUrl(String audioUrl);

    /**
     * Inserts a complete record (a copied narration) unless one already exists for its key.
     *
     * @return false when the key was already taken, leaving the existing record untouched
     */
    boolean insertIfAbsent(StepNarration narration);

    /** Deletes the record and returns it, so its audio can be deleted too. */
    Optional<StepNarration> delete(String narrationKey);

    record Claim(
            String narrationKey,
            Long recipeId,
            Long processId,
            String stepId,
            String sourceTextHash,
            String token,
            Long userId,
            Instant now,
            Instant leaseUntil,
            boolean force
    ) {
    }

    record ReadyAudio(
            String audioUrl,
            String storageBackend,
            String storagePath,
            String mimeType,
            String format,
            Long durationMs,
            long sizeBytes,
            String provider,
            String modelKey,
            String providerModelId,
            String voice,
            String languageCode,
            Instant generatedAt
    ) {
    }
}
