package com.processVisualisation.virtualKitchen.ai.recipe.narration;

import com.processVisualisation.virtualKitchen.ai.provider.tts.AudioFormat;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.model.StepNarration;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.model.StepNarrationStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.storage.NarrationAudioStorage;
import com.processVisualisation.virtualKitchen.recipe.event.RecipeCopiedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Add to kitchen" copies a recipe's narration onto the copy's new recipe, process and step ids. */
class StepNarrationCopierTest {

    private static final Long SOURCE_RECIPE = 46L;
    private static final Long TARGET_RECIPE = 47L;
    private static final byte[] AUDIO = {1, 2, 3, 4};

    private InMemoryStepNarrationStore store;
    private MapAudioStorage storage;
    private StepNarrationCopier copier;

    @BeforeEach
    void setUp() {
        store = new InMemoryStepNarrationStore();
        storage = new MapAudioStorage();
        copier = new StepNarrationCopier(store, storage, Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void readyNarrationIsCopiedOntoTheNewIdsWithItsOwnAudio() {
        StepNarration source = ready(SOURCE_RECIPE, 57L, "step-a", storage.put(AUDIO));

        copier.onRecipeCopied(event(Map.of(57L, 60L), Map.of(57L, Map.of("step-a", "step-a2"))));

        StepNarration copy = store.find(StepNarrationService.narrationKey(TARGET_RECIPE, "step-a2")).orElseThrow();
        assertEquals(TARGET_RECIPE, copy.getRecipeId());
        assertEquals(60L, copy.getProcessId());
        assertEquals("step-a2", copy.getStepId());
        assertEquals(StepNarrationStatus.READY, copy.getStatus());
        // Same text hash: the copied step narrates the same text, so it reads as READY, not STALE.
        assertEquals(source.getSourceTextHash(), copy.getSourceTextHash());
        assertEquals("Kore", copy.getVoice());
        // Its own object, so regenerating or deleting the original never breaks the copy's audio.
        assertNotEquals(source.getStoragePath(), copy.getStoragePath());
        assertArrayEquals(AUDIO, storage.read(copy.getStoragePath()).orElseThrow());
        // The original is untouched.
        assertEquals(source.getStoragePath(), store.find(source.getNarrationKey()).orElseThrow().getStoragePath());
    }

    @Test
    void stepWhoseRecordedProcessIsStaleIsFoundInAnyCopiedProcess() {
        ready(SOURCE_RECIPE, 999L, "step-b", storage.put(AUDIO));

        copier.onRecipeCopied(event(Map.of(58L, 61L), Map.of(58L, Map.of("step-b", "step-b2"))));

        assertEquals(61L, store.find(StepNarrationService.narrationKey(TARGET_RECIPE, "step-b2")).orElseThrow().getProcessId());
    }

    @Test
    void narrationWhoseAudioIsGoneIsSkipped() {
        ready(SOURCE_RECIPE, 57L, "step-a", "missing.wav");

        copier.onRecipeCopied(event(Map.of(57L, 60L), Map.of(57L, Map.of("step-a", "step-a2"))));

        assertTrue(store.find(StepNarrationService.narrationKey(TARGET_RECIPE, "step-a2")).isEmpty());
    }

    @Test
    void existingNarrationOnTheCopyIsKeptAndTheExtraObjectDropped() {
        ready(SOURCE_RECIPE, 57L, "step-a", storage.put(AUDIO));
        StepNarration existing = ready(TARGET_RECIPE, 60L, "step-a2", storage.put(new byte[]{9}));
        int objectsBefore = storage.files.size();

        copier.onRecipeCopied(event(Map.of(57L, 60L), Map.of(57L, Map.of("step-a", "step-a2"))));

        assertEquals(existing.getStoragePath(), store.find(existing.getNarrationKey()).orElseThrow().getStoragePath());
        assertEquals(objectsBefore, storage.files.size());
    }

    @Test
    void failedAndOtherRecipesNarrationAreNotCopied() {
        StepNarration failed = ready(SOURCE_RECIPE, 57L, "step-a", storage.put(AUDIO));
        store.claim(new StepNarrationStore.Claim(failed.getNarrationKey(), SOURCE_RECIPE, 57L, "step-a", "other-hash",
                "token-2", 1L, Instant.now(), Instant.now().plusSeconds(60), true));
        store.markFailed(failed.getNarrationKey(), "token-2", "boom", Instant.now());
        ready(99L, 57L, "step-c", storage.put(AUDIO));

        copier.onRecipeCopied(event(Map.of(57L, 60L), Map.of(57L, Map.of("step-a", "step-a2", "step-c", "step-c2"))));

        assertFalse(store.find(StepNarrationService.narrationKey(TARGET_RECIPE, "step-a2")).isPresent());
        assertFalse(store.find(StepNarrationService.narrationKey(TARGET_RECIPE, "step-c2")).isPresent());
    }

    @Test
    void copyTo_writesAnotherRecipesStepItsOwnAudio_underTheGivenTextFingerprint() {
        StepNarration source = ready(SOURCE_RECIPE, 57L, "step-a", storage.put(AUDIO));

        StepNarrationCopier.CopyOutcome outcome = copier.copyTo(source, 300L, 70L, "step-a", "hash-of-imported-step", false);

        assertEquals(StepNarrationCopier.CopyOutcome.COPIED, outcome);
        StepNarration copy = store.find(StepNarrationService.narrationKey(300L, "step-a")).orElseThrow();
        assertEquals(300L, copy.getRecipeId());
        assertEquals(70L, copy.getProcessId());
        assertEquals("hash-of-imported-step", copy.getSourceTextHash());
        assertNotEquals(source.getStoragePath(), copy.getStoragePath());
        assertNotEquals(source.getAudioUrl(), copy.getAudioUrl());
        assertArrayEquals(AUDIO, storage.read(copy.getStoragePath()).orElseThrow());
        // The copy is found by its own URL, the source still by its own.
        assertEquals(copy.getNarrationKey(), store.findReadyByAudioUrl(copy.getAudioUrl()).orElseThrow().getNarrationKey());
        assertEquals(source.getNarrationKey(), store.findReadyByAudioUrl(source.getAudioUrl()).orElseThrow().getNarrationKey());
    }

    @Test
    void copyTo_neverSharesAUrlWhenSharingIsNotAllowed_andReportsWhatHappened() {
        StepNarration gone = ready(SOURCE_RECIPE, 57L, "step-a", "missing.wav");
        StepNarration elsewhere = ready(SOURCE_RECIPE, 57L, "step-b", storage.put(AUDIO));
        elsewhere.setStorageBackend("another-backend");
        StepNarration fine = ready(SOURCE_RECIPE, 57L, "step-c", storage.put(AUDIO));
        ready(300L, 70L, "step-c", storage.put(new byte[]{9}));
        int objectsBefore = storage.files.size();

        assertEquals(StepNarrationCopier.CopyOutcome.AUDIO_UNAVAILABLE, copier.copyTo(gone, 300L, 70L, "step-a", "h", false));
        assertEquals(StepNarrationCopier.CopyOutcome.AUDIO_UNAVAILABLE, copier.copyTo(elsewhere, 300L, 70L, "step-b", "h", false));
        assertEquals(StepNarrationCopier.CopyOutcome.ALREADY_NARRATED, copier.copyTo(fine, 300L, 70L, "step-c", "h", false));

        assertTrue(store.find(StepNarrationService.narrationKey(300L, "step-a")).isEmpty());
        assertTrue(store.find(StepNarrationService.narrationKey(300L, "step-b")).isEmpty());
        assertEquals(objectsBefore, storage.files.size(), "no object is left behind");
    }

    private static RecipeCopiedEvent event(Map<Long, Long> processIds, Map<Long, Map<String, String>> nodeIds) {
        return new RecipeCopiedEvent(SOURCE_RECIPE, TARGET_RECIPE, 2L, processIds, nodeIds);
    }

    private StepNarration ready(Long recipeId, Long processId, String stepId, String storagePath) {
        String key = StepNarrationService.narrationKey(recipeId, stepId);
        Instant now = Instant.parse("2026-10-18T15:51:00Z");
        store.claim(new StepNarrationStore.Claim(key, recipeId, processId, stepId, "hash-" + stepId, "token-1", 1L,
                now, now.plusSeconds(300), false));
        store.commitReady(key, "token-1", new StepNarrationStore.ReadyAudio(
                "https://audio.example/" + storagePath, storage.backendName(), storagePath, "audio/wav", "WAV",
                1000L, AUDIO.length, "gemini", "gemini-tts", "gemini-2.5-flash-preview-tts", "Kore", "en-US", now));
        return store.find(key).orElseThrow();
    }

    private static final class MapAudioStorage implements NarrationAudioStorage {
        private final Map<String, byte[]> files = new HashMap<>();
        private int counter;

        String put(byte[] data) {
            String path = "audio-" + (++counter) + ".wav";
            files.put(path, data);
            return path;
        }

        @Override
        public String backendName() {
            return "map";
        }

        @Override
        public StoredAudio store(byte[] data, AudioFormat format, String narrationKey) {
            String path = put(data);
            return new StoredAudio("https://audio.example/" + path, "map", path);
        }

        @Override
        public void delete(String storagePath) {
            files.remove(storagePath);
        }

        @Override
        public Optional<byte[]> read(String storagePath) {
            return Optional.ofNullable(files.get(storagePath));
        }
    }
}
