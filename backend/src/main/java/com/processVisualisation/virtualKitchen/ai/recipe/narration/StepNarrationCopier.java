package com.processVisualisation.virtualKitchen.ai.recipe.narration;

import com.processVisualisation.virtualKitchen.ai.provider.tts.AudioFormat;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.model.StepNarration;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.model.StepNarrationStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.storage.NarrationAudioStorage;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.storage.NarrationAudioStorage.StoredAudio;
import com.processVisualisation.virtualKitchen.recipe.event.RecipeCopiedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Copies a recipe's READY step narration onto its copy ("Add to kitchen"), so the copy plays
 * narration straight away instead of showing every step as not generated.
 * <p>
 * Narration is keyed by {@code recipeId::stepId}, and a copied recipe gets new process and step
 * ids, so each record is re-keyed through the copy's id maps. The source text hash is kept as is:
 * it fingerprints only the narrated text, which the copied step shares, so the copy reads as READY
 * rather than STALE. The audio is stored again as its own object, because the original's audio is
 * deleted whenever the original step is regenerated or its narration deleted.
 * <p>
 * Best effort: a narration that cannot be copied is skipped (that step simply regenerates on
 * demand), and nothing here can fail the recipe copy itself.
 */
@Component
public class StepNarrationCopier {

    private static final Logger log = LoggerFactory.getLogger(StepNarrationCopier.class);

    private final StepNarrationStore store;
    private final NarrationAudioStorage storage;
    private final Clock clock;

    @Autowired
    public StepNarrationCopier(StepNarrationStore store, NarrationAudioStorage storage) {
        this(store, storage, Clock.systemUTC());
    }

    StepNarrationCopier(StepNarrationStore store, NarrationAudioStorage storage, Clock clock) {
        this.store = store;
        this.storage = storage;
        this.clock = clock;
    }

    @EventListener
    public void onRecipeCopied(RecipeCopiedEvent event) {
        int copied = 0;
        int skipped = 0;
        try {
            for (StepNarration source : store.findReadyByRecipeId(event.sourceRecipeId())) {
                try {
                    if (copy(source, event)) {
                        copied++;
                    } else {
                        skipped++;
                    }
                } catch (RuntimeException e) {
                    skipped++;
                    log.warn("event=narration_copy_failed narrationKey={} targetRecipeId={} errorType={} error=\"{}\"",
                            source.getNarrationKey(), event.targetRecipeId(), e.getClass().getSimpleName(), e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.warn("event=narration_copy_failed sourceRecipeId={} targetRecipeId={} errorType={} error=\"{}\"",
                    event.sourceRecipeId(), event.targetRecipeId(), e.getClass().getSimpleName(), e.getMessage());
        }
        log.info("event=narration_copied sourceRecipeId={} targetRecipeId={} copied={} skipped={}",
                event.sourceRecipeId(), event.targetRecipeId(), copied, skipped);
    }

    /** @return true when a narration was written for the copied step */
    private boolean copy(StepNarration source, RecipeCopiedEvent event) {
        Optional<Target> target = targetOf(source, event);
        if (target.isEmpty()) {
            log.debug("event=narration_copy_skipped narrationKey={} reason=step_not_copied", source.getNarrationKey());
            return false;
        }
        // Audio held by a storage backend this instance no longer writes to is shared by URL here: the
        // copy of a public recipe may keep pointing at it, as its path is left unset and so never deleted.
        return copyTo(source, event.targetRecipeId(), target.get().processId(), target.get().stepId(),
                source.getSourceTextHash(), true) == CopyOutcome.COPIED;
    }

    /** What became of one narration copy. */
    public enum CopyOutcome {
        COPIED,
        /** The source's audio could not be read (deleted, or held by another storage backend). */
        AUDIO_UNAVAILABLE,
        /** The target step already has narration; it is kept. */
        ALREADY_NARRATED
    }

    /**
     * Copies one READY narration onto another recipe's step: its audio is stored again as a new object and a
     * new record is written for the target step. Nothing of the source is changed.
     *
     * @param sourceTextHash the fingerprint of the target step's spoken text; the copy reads as READY only
     *                       while the target step still says the same thing
     * @param allowSharedUrl whether audio that cannot be read back may be shared by URL instead of failing
     *                       (never for an import, whose recipe must own every file it plays)
     */
    public CopyOutcome copyTo(StepNarration source, Long targetRecipeId, Long targetProcessId, String targetStepId,
                              String sourceTextHash, boolean allowSharedUrl) {
        String key = StepNarrationService.narrationKey(targetRecipeId, targetStepId);

        StoredAudio audio;
        if (source.getStoragePath() != null && storage.backendName().equals(source.getStorageBackend())) {
            Optional<byte[]> data = storage.read(source.getStoragePath());
            if (data.isEmpty() || data.get().length == 0) {
                log.debug("event=narration_copy_skipped narrationKey={} reason=audio_missing", source.getNarrationKey());
                return CopyOutcome.AUDIO_UNAVAILABLE;
            }
            audio = storage.store(data.get(), formatOf(source), key);
        } else if (allowSharedUrl) {
            // Leave the path unset so deleting the copy can never delete the original's object.
            audio = new StoredAudio(source.getAudioUrl(), source.getStorageBackend(), null);
        } else {
            return CopyOutcome.AUDIO_UNAVAILABLE;
        }

        Instant now = Instant.now(clock);
        StepNarration copy = new StepNarration();
        copy.setId(UUID.randomUUID().toString());
        copy.setNarrationKey(key);
        copy.setRecipeId(targetRecipeId);
        copy.setProcessId(targetProcessId);
        copy.setStepId(targetStepId);
        copy.setStatus(StepNarrationStatus.READY);
        copy.setSourceTextHash(sourceTextHash);
        copy.setAudioUrl(audio.url());
        copy.setStorageBackend(audio.backend());
        copy.setStoragePath(audio.path());
        copy.setMimeType(source.getMimeType());
        copy.setFormat(source.getFormat());
        copy.setDurationMs(source.getDurationMs());
        copy.setSizeBytes(source.getSizeBytes());
        copy.setProvider(source.getProvider());
        copy.setModelKey(source.getModelKey());
        copy.setProviderModelId(source.getProviderModelId());
        copy.setVoice(source.getVoice());
        copy.setLanguageCode(source.getLanguageCode());
        copy.setGenerationToken(UUID.randomUUID().toString());
        copy.setGeneratedByUserId(source.getGeneratedByUserId());
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        copy.setGeneratedAt(source.getGeneratedAt());

        if (!store.insertIfAbsent(copy)) {
            // The copied step already has narration (generated meanwhile): keep it, drop our object.
            if (audio.path() != null) {
                storage.delete(audio.path());
            }
            return CopyOutcome.ALREADY_NARRATED;
        }
        return CopyOutcome.COPIED;
    }

    /**
     * The copied process and step for {@code source}. Looks the step up under its recorded process
     * first, then in every copied process, since only the step id is part of the narration key.
     */
    private static Optional<Target> targetOf(StepNarration source, RecipeCopiedEvent event) {
        Map<Long, Map<String, String>> nodeIdMaps = event.nodeIdMaps() == null ? Map.of() : event.nodeIdMaps();
        Long sourceProcessId = source.getProcessId();
        if (sourceProcessId != null) {
            String stepId = nodeIdMaps.getOrDefault(sourceProcessId, Map.of()).get(source.getStepId());
            Long processId = event.processIdMap().get(sourceProcessId);
            if (stepId != null && processId != null) {
                return Optional.of(new Target(processId, stepId));
            }
        }
        for (Map.Entry<Long, Map<String, String>> entry : nodeIdMaps.entrySet()) {
            String stepId = entry.getValue().get(source.getStepId());
            Long processId = event.processIdMap().get(entry.getKey());
            if (stepId != null && processId != null) {
                return Optional.of(new Target(processId, stepId));
            }
        }
        return Optional.empty();
    }

    private static AudioFormat formatOf(StepNarration source) {
        if (source.getFormat() != null) {
            try {
                return AudioFormat.valueOf(source.getFormat());
            } catch (IllegalArgumentException ignored) {
                // fall through to the MIME type
            }
        }
        AudioFormat byMime = AudioFormat.fromMimeType(source.getMimeType());
        return byMime != null ? byMime : AudioFormat.WAV;
    }

    private record Target(Long processId, String stepId) {
    }
}
