package com.processVisualisation.virtualKitchen.ai.recipe.narration;

import com.processVisualisation.virtualKitchen.ai.recipe.narration.model.StepNarration;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.model.StepNarrationStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link StepNarrationStore} with the same claim/commit rules as
 * {@link MongoStepNarrationStore}. Every operation is synchronized, so each one is atomic as its
 * Mongo counterpart is. Records are copied on the way in and out, so callers cannot mutate stored
 * state behind the store's back.
 */
class InMemoryStepNarrationStore implements StepNarrationStore {

    private final Map<String, StepNarration> records = new LinkedHashMap<>();

    @Override
    public synchronized Optional<StepNarration> find(String narrationKey) {
        return Optional.ofNullable(records.get(narrationKey)).map(InMemoryStepNarrationStore::copy);
    }

    @Override
    public synchronized List<StepNarration> findAll(Collection<String> narrationKeys) {
        return narrationKeys.stream().map(records::get).filter(r -> r != null).map(InMemoryStepNarrationStore::copy).toList();
    }

    @Override
    public synchronized Optional<StepNarration> claim(Claim claim) {
        StepNarration record = records.get(claim.narrationKey());
        if (record == null) {
            record = new StepNarration();
            record.setId(UUID.randomUUID().toString());
            record.setNarrationKey(claim.narrationKey());
            record.setRecipeId(claim.recipeId());
            record.setStepId(claim.stepId());
            record.setCreatedAt(claim.now());
            records.put(claim.narrationKey(), record);
        } else {
            boolean sameHash = claim.sourceTextHash().equals(record.getSourceTextHash());
            boolean activeSame = sameHash && record.getStatus() == StepNarrationStatus.GENERATING
                    && record.getLeaseUntil() != null && record.getLeaseUntil().isAfter(claim.now());
            boolean readySame = sameHash && record.getStatus() == StepNarrationStatus.READY;
            if (activeSame || (!claim.force() && readySame)) {
                return Optional.empty();
            }
        }
        record.setStatus(StepNarrationStatus.GENERATING);
        record.setSourceTextHash(claim.sourceTextHash());
        record.setGenerationToken(claim.token());
        record.setLeaseUntil(claim.leaseUntil());
        record.setGeneratedByUserId(claim.userId());
        record.setProcessId(claim.processId());
        record.setUpdatedAt(claim.now());
        record.setAttempts(record.getAttempts() + 1);
        record.setFailureReason(null);
        record.setFailedAt(null);
        return Optional.of(copy(record));
    }

    @Override
    public synchronized Optional<StepNarration> commitReady(String narrationKey, String token, ReadyAudio audio) {
        StepNarration record = records.get(narrationKey);
        if (record == null || !token.equals(record.getGenerationToken())) {
            return Optional.empty();
        }
        StepNarration previous = copy(record);
        record.setStatus(StepNarrationStatus.READY);
        record.setAudioUrl(audio.audioUrl());
        record.setStorageBackend(audio.storageBackend());
        record.setStoragePath(audio.storagePath());
        record.setMimeType(audio.mimeType());
        record.setFormat(audio.format());
        record.setDurationMs(audio.durationMs());
        record.setSizeBytes(audio.sizeBytes());
        record.setProvider(audio.provider());
        record.setModelKey(audio.modelKey());
        record.setProviderModelId(audio.providerModelId());
        record.setVoice(audio.voice());
        record.setLanguageCode(audio.languageCode());
        record.setGeneratedAt(audio.generatedAt());
        record.setUpdatedAt(audio.generatedAt());
        record.setLeaseUntil(null);
        record.setFailureReason(null);
        record.setFailedAt(null);
        return Optional.of(previous);
    }

    @Override
    public synchronized boolean markFailed(String narrationKey, String token, String reason, Instant failedAt) {
        StepNarration record = records.get(narrationKey);
        if (record == null || !token.equals(record.getGenerationToken())) {
            return false;
        }
        record.setStatus(StepNarrationStatus.FAILED);
        record.setFailureReason(reason);
        record.setFailedAt(failedAt);
        record.setUpdatedAt(failedAt);
        record.setLeaseUntil(null);
        return true;
    }

    @Override
    public synchronized List<StepNarration> findReadyByRecipeId(Long recipeId) {
        return records.values().stream()
                .filter(r -> recipeId.equals(r.getRecipeId()) && r.getStatus() == StepNarrationStatus.READY)
                .map(InMemoryStepNarrationStore::copy)
                .toList();
    }

    @Override
    public synchronized Optional<StepNarration> findReadyByAudioUrl(String audioUrl) {
        return records.values().stream()
                .filter(r -> audioUrl.equals(r.getAudioUrl()) && r.getStatus() == StepNarrationStatus.READY)
                .map(InMemoryStepNarrationStore::copy)
                .findFirst();
    }

    @Override
    public synchronized boolean insertIfAbsent(StepNarration narration) {
        if (records.containsKey(narration.getNarrationKey())) {
            return false;
        }
        records.put(narration.getNarrationKey(), copy(narration));
        return true;
    }

    @Override
    public synchronized Optional<StepNarration> delete(String narrationKey) {
        return Optional.ofNullable(records.remove(narrationKey));
    }

    private static StepNarration copy(StepNarration source) {
        StepNarration copy = new StepNarration();
        copy.setId(source.getId());
        copy.setNarrationKey(source.getNarrationKey());
        copy.setRecipeId(source.getRecipeId());
        copy.setProcessId(source.getProcessId());
        copy.setStepId(source.getStepId());
        copy.setStatus(source.getStatus());
        copy.setSourceTextHash(source.getSourceTextHash());
        copy.setAudioUrl(source.getAudioUrl());
        copy.setStorageBackend(source.getStorageBackend());
        copy.setStoragePath(source.getStoragePath());
        copy.setMimeType(source.getMimeType());
        copy.setFormat(source.getFormat());
        copy.setDurationMs(source.getDurationMs());
        copy.setSizeBytes(source.getSizeBytes());
        copy.setProvider(source.getProvider());
        copy.setModelKey(source.getModelKey());
        copy.setProviderModelId(source.getProviderModelId());
        copy.setVoice(source.getVoice());
        copy.setLanguageCode(source.getLanguageCode());
        copy.setGenerationToken(source.getGenerationToken());
        copy.setLeaseUntil(source.getLeaseUntil());
        copy.setGeneratedByUserId(source.getGeneratedByUserId());
        copy.setFailureReason(source.getFailureReason());
        copy.setFailedAt(source.getFailedAt());
        copy.setAttempts(source.getAttempts());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setGeneratedAt(source.getGeneratedAt());
        return copy;
    }
}
