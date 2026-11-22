package com.processVisualisation.virtualKitchen.ai.recipe.narration;

import com.processVisualisation.virtualKitchen.common.web.UserFacingErrors;
import com.processVisualisation.virtualKitchen.common.logging.UsageLedger;

import com.processVisualisation.virtualKitchen.ai.platform.analytics.AiAnalyticsRecorder;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifactKeyBuilder;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifactService;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifactSpec;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.codec.GeneratedAudioCodec;
import com.processVisualisation.virtualKitchen.ai.platform.dispatch.AiClientResolver;
import com.processVisualisation.virtualKitchen.ai.platform.logging.OperationLog;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestOutcome;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestQueueService;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.provider.tts.GeneratedAudio;
import com.processVisualisation.virtualKitchen.ai.provider.tts.TtsRequest;
import com.processVisualisation.virtualKitchen.ai.recipe.RecipeProcessAiException;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.NarrationScriptBuilder.NarrationScript;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.dto.StepNarrationResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.model.StepNarration;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.model.StepNarrationStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.storage.NarrationAudioStorage;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationKeyBuilder;
import com.processVisualisation.virtualKitchen.common.concurrent.NamedTask;
import com.processVisualisation.virtualKitchen.common.concurrent.TaskPool;
import com.processVisualisation.virtualKitchen.common.logging.FailureLogger;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import com.processVisualisation.virtualKitchen.recipe.model.Visibility;
import com.processVisualisation.virtualKitchen.recipe.repository.RecipeTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Narration for recipe steps: decides whether a step's narration can be reused, is stale, or has
 * to be generated, and runs generation without ever paying twice for the same text.
 * <p>
 * The flow for every step:
 * <pre>
 *   saved step  →  script + hash (NarrationScriptBuilder)
 *               →  READY with the same hash?  → reuse (cache hit, no provider call)
 *               →  otherwise claim the step (StepNarrationStore) → generate on the narration pool
 *                  → AiRequestQueueService (model registry picks the TTS provider, credits, retries,
 *                    artifact staging) → NarrationAudioArtifactConsumer stores audio, marks READY
 * </pre>
 * Staleness is computed when narration is read: the current script hash is compared with the hash
 * the audio was made from. The recipe editor and its persistence never call into this class.
 * <p>
 * Nothing here knows which provider speaks. Switching provider is
 * {@code ai.default-model.TEXT_TO_SPEECH}, and adding one is a new {@code TtsProvider} bean plus a
 * registry entry.
 */
@Service
public class StepNarrationService {

    static final String CORRELATION_TYPE = "step-narration";

    private static final Logger log = LoggerFactory.getLogger(StepNarrationService.class);

    private final RecipeTemplateRepository recipeTemplateRepository;
    private final RecipeProcessVisualizationService stepSource;
    private final NarrationScriptBuilder scriptBuilder;
    private final StepNarrationStore store;
    private final AiRequestQueueService queueService;
    private final AiClientResolver clientResolver;
    private final GeneratedAudioCodec audioCodec;
    private final NarrationAudioArtifactConsumer audioConsumer;
    private final AiArtifactService artifactService;
    private final NarrationAudioStorage storage;
    private final NarrationProperties properties;
    private final TaskPool narrationTaskPool;
    private final Clock clock;

    private AiAnalyticsRecorder analytics = AiAnalyticsRecorder.disabled();

    /** Optional, so tests can construct this without analytics. */
    @Autowired(required = false)
    public void setAnalyticsRecorder(AiAnalyticsRecorder analytics) {
        this.analytics = analytics;
    }

    @Autowired
    public StepNarrationService(
            RecipeTemplateRepository recipeTemplateRepository,
            RecipeProcessVisualizationService stepSource,
            NarrationScriptBuilder scriptBuilder,
            StepNarrationStore store,
            AiRequestQueueService queueService,
            AiClientResolver clientResolver,
            GeneratedAudioCodec audioCodec,
            NarrationAudioArtifactConsumer audioConsumer,
            AiArtifactService artifactService,
            NarrationAudioStorage storage,
            NarrationProperties properties,
            @Qualifier("narrationTaskPool") TaskPool narrationTaskPool) {
        this(recipeTemplateRepository, stepSource, scriptBuilder, store, queueService, clientResolver, audioCodec,
                audioConsumer, artifactService, storage, properties, narrationTaskPool, Clock.systemUTC());
    }

    StepNarrationService(
            RecipeTemplateRepository recipeTemplateRepository,
            RecipeProcessVisualizationService stepSource,
            NarrationScriptBuilder scriptBuilder,
            StepNarrationStore store,
            AiRequestQueueService queueService,
            AiClientResolver clientResolver,
            GeneratedAudioCodec audioCodec,
            NarrationAudioArtifactConsumer audioConsumer,
            AiArtifactService artifactService,
            NarrationAudioStorage storage,
            NarrationProperties properties,
            TaskPool narrationTaskPool,
            Clock clock) {
        this.recipeTemplateRepository = recipeTemplateRepository;
        this.stepSource = stepSource;
        this.scriptBuilder = scriptBuilder;
        this.store = store;
        this.queueService = queueService;
        this.clientResolver = clientResolver;
        this.audioCodec = audioCodec;
        this.audioConsumer = audioConsumer;
        this.artifactService = artifactService;
        this.storage = storage;
        this.properties = properties;
        this.narrationTaskPool = narrationTaskPool;
        this.clock = clock;
    }

    /** Narration state of every STEP in the process, in step order. Never generates anything. */
    public List<StepNarrationResponseDTO> list(Long userId, Long recipeId, Long processId) {
        requireViewable(recipeId, userId);
        Map<String, NarrationScript> scripts = loadScripts(recipeId, processId);
        Map<String, StepNarration> records = store.findAll(
                        scripts.keySet().stream().map(stepId -> narrationKey(recipeId, stepId)).toList())
                .stream()
                .collect(Collectors.toMap(StepNarration::getStepId, Function.identity(), (a, b) -> a));
        Instant now = now();
        List<StepNarrationResponseDTO> result = new ArrayList<>();
        scripts.forEach((stepId, script) -> result.add(toDto(stepId, records.get(stepId), script, now)));
        return result;
    }

    /** Narration state of one step. Never generates anything. */
    public StepNarrationResponseDTO get(Long userId, Long recipeId, Long processId, String stepId) {
        requireViewable(recipeId, userId);
        NarrationScript script = requireStep(loadScripts(recipeId, processId), stepId);
        return toDto(stepId, store.find(narrationKey(recipeId, stepId)).orElse(null), script, now());
    }

    /**
     * Returns usable narration for the step, starting generation only when there is none for its
     * current text. Generation runs in the background, so this returns GENERATING immediately and
     * the caller polls. Concurrent calls for the same step start at most one generation.
     *
     * @param force regenerate even if READY (or recently FAILED); restricted to the recipe owner
     */
    public StepNarrationResponseDTO ensure(Long userId, Long recipeId, Long processId, String stepId, boolean force) {
        if (force) {
            requireOwned(recipeId, userId);
        } else {
            requireViewable(recipeId, userId);
        }
        NarrationScript script = requireStep(loadScripts(recipeId, processId), stepId);
        return ensureStep(userId, recipeId, processId, stepId, script, force);
    }

    /**
     * {@link #ensure} for several steps (all of them when {@code stepIds} is empty). Each step is
     * independent: a step that fails never affects the others or narration already READY.
     */
    public List<StepNarrationResponseDTO> ensureAll(Long userId, Long recipeId, Long processId, List<String> stepIds) {
        return ensureAll(userId, recipeId, processId, stepIds, false);
    }

    /**
     * {@link #ensureAll(Long, Long, Long, List)}; with {@code force}, steps whose narration is already READY are
     * narrated again and replaced ("regenerate and replace", owner only — see {@link #ensure}).
     */
    public List<StepNarrationResponseDTO> ensureAll(Long userId, Long recipeId, Long processId, List<String> stepIds,
                                                    boolean force) {
        if (force) {
            requireOwned(recipeId, userId);
        } else {
            requireViewable(recipeId, userId);
        }
        Map<String, NarrationScript> scripts = loadScripts(recipeId, processId);
        List<String> targets = stepIds == null || stepIds.isEmpty() ? new ArrayList<>(scripts.keySet()) : stepIds;
        log.info("event=narration_batch_requested recipeId={} processId={} steps={}", recipeId, processId, targets.size());

        List<StepNarrationResponseDTO> result = new ArrayList<>();
        for (String stepId : targets) {
            NarrationScript script = scripts.get(stepId);
            if (script == null) {
                continue;
            }
            try {
                result.add(ensureStep(userId, recipeId, processId, stepId, script, force));
            } catch (RuntimeException e) {
                FailureLogger.logFailure(log, "narration_batch_step_failed", e,
                        "narrationKey=" + narrationKey(recipeId, stepId));
                result.add(StepNarrationResponseDTO.builder()
                        .stepId(stepId)
                        .status(StepNarrationStatus.FAILED.name())
                        .narratable(true)
                        .text(script.text())
                        .failureReason(UserFacingErrors.forJob(describeFailure(e)))
                        .build());
            }
        }
        return result;
    }

    /** Deletes a step's narration and its audio (owner only); the next ensure generates afresh. */
    public void delete(Long userId, Long recipeId, Long processId, String stepId) {
        requireOwned(recipeId, userId);
        loadScripts(recipeId, processId); // validates the process belongs to the recipe
        String key = narrationKey(recipeId, stepId);
        store.delete(key).ifPresent(removed -> {
            if (removed.getStoragePath() != null && storage.backendName().equals(removed.getStorageBackend())) {
                storage.delete(removed.getStoragePath());
            }
            log.info("event=narration_deleted narrationKey={} recipeId={} stepId={}", key, recipeId, stepId);
        });
    }

    private StepNarrationResponseDTO ensureStep(
            Long userId, Long recipeId, Long processId, String stepId, NarrationScript script, boolean force) {
        String key = narrationKey(recipeId, stepId);
        Instant now = now();
        if (!script.narratable()) {
            return toDto(stepId, null, script, now);
        }

        Optional<StepNarration> existing = store.find(key);
        if (existing.isPresent() && !force) {
            StepNarration record = existing.get();
            boolean sameText = script.hash().equals(record.getSourceTextHash());
            if (sameText && record.getStatus() == StepNarrationStatus.READY) {
                UsageLedger.increment(UsageLedger.Counter.CACHE_HITS);
                log.debug("event=narration_cache_hit narrationKey={}", key);
                return toDto(stepId, record, script, now);
            }
            if (sameText && isActive(record, now)) {
                log.debug("event=narration_generation_joined narrationKey={} reason=already_generating", key);
                return toDto(stepId, record, script, now);
            }
            if (sameText && inFailureBackoff(record, now)) {
                log.debug("event=narration_retry_deferred narrationKey={} retryAfter={}", key, retryAfter(record));
                return toDto(stepId, record, script, now);
            }
            if (!sameText && record.getStatus() == StepNarrationStatus.READY) {
                log.debug("event=narration_stale narrationKey={}", key);
            }
        }
        log.debug("event=narration_cache_miss narrationKey={} reason={}", key,
                force ? "forced" : existing.map(r -> "status_" + r.getStatus().name().toLowerCase()).orElse("not_generated"));

        String token = UUID.randomUUID().toString();
        Optional<StepNarration> claimed = store.claim(new StepNarrationStore.Claim(
                key, recipeId, processId, stepId, script.hash(), token, userId,
                now, now.plusMillis(properties.getLeaseMs()), force));
        if (claimed.isEmpty()) {
            // Another request claimed this text first; report its state instead of generating again.
            log.debug("event=narration_generation_joined narrationKey={} reason=claim_lost", key);
            return toDto(stepId, store.find(key).orElse(null), script, now);
        }

        narrationTaskPool.submit(new NamedTask<>("narration:" + key, () -> {
            generate(userId, key, token, script, force);
            return null;
        }));
        log.debug("event=narration_generation_requested narrationKey={} chars={} force={}", key, script.text().length(), force);
        return toDto(stepId, claimed.get(), script, now);
    }

    /**
     * Runs on the narration pool. Every failure is recorded on the claim's record (never thrown),
     * and only while this generation still owns the claim.
     */
    void generate(Long userId, String key, String token, NarrationScript script, boolean force) {
        // The task may have waited in the pool queue past the claim's lease, and another request may have re-claimed
        // the step meanwhile: generate (and pay) only while this claim is still ours, with a fresh lease from now.
        Instant start = now();
        if (!store.renewLease(key, token, start.plusMillis(properties.getLeaseMs()), start)) {
            log.info("event=narration_generation_skipped narrationKey={} reason=claim_lost_while_queued", key);
            return;
        }
        OperationLog operation = OperationLog.start(log, "narration_generation", "narration:" + key,
                "narrationKey=" + key + " chars=" + script.text().length() + " force=" + force);
        try {
            // A forced regeneration must not be served the previous audio from the artifact store.
            String[] keyInputs = force ? new String[]{script.hash(), token} : new String[]{script.hash()};
            AiArtifactSpec<GeneratedAudio> artifactSpec = AiArtifactSpec.of(
                    AiArtifactKeyBuilder.build(AiCapability.TEXT_TO_SPEECH, CORRELATION_TYPE, key, keyInputs),
                    audioCodec,
                    NarrationAudioArtifactConsumer.CONSUMER_ID);

            AiRequestOutcome<GeneratedAudio> outcome = queueService.executeInline(
                    userId,
                    AiCapability.TEXT_TO_SPEECH,
                    null,
                    null,
                    CORRELATION_TYPE,
                    NarrationAudioArtifactConsumer.correlationId(key, token),
                    artifactSpec,
                    selection -> {
                        log.debug("event=narration_provider_selected narrationKey={} modelKey={} providerBean={} usedFallback={}",
                                key, selection.model().getKey(), selection.model().getProviderBean(), selection.usedFallback());
                        return clientResolver.resolveTtsProvider(selection.model()).synthesize(new TtsRequest(
                                script.text(),
                                selection.model().getProviderModelId(),
                                StringUtils.hasText(properties.getVoice()) ? properties.getVoice() : null,
                                properties.getLanguageCode(),
                                null));
                    });

            GeneratedAudio audio = outcome.value();
            if (audio == null || audio.data() == null || audio.data().length == 0) {
                throw new IllegalStateException("TTS provider returned no audio");
            }
            String url = audioConsumer.commit(key, token, outcome.selection().model().getKey(), audio);
            if (url != null) {
                analytics.outputStored(outcome.jobId(), "AUDIO", "step_narration", key, url, audio.mimeType(),
                        (long) audio.data().length);
            }
            if (outcome.artifact() != null) {
                artifactService.markConsumed(outcome.artifact().getId());
            }
            operation.completed("narrationKey=" + key + " modelKey=" + outcome.selection().model().getKey()
                    + " usedFallback=" + outcome.selection().usedFallback() + " voice=" + audio.voice()
                    + " audioMs=" + audio.durationMs() + " bytes=" + audio.data().length
                    + " reusedArtifact=" + outcome.reused()
                    // false = the step was edited (or the lease taken over) mid-generation; newer audio won
                    + " committed=" + (url != null));
        } catch (Exception e) {
            operation.failed(e, "narrationKey=" + key);
            store.markFailed(key, token, describeFailure(e), now());
        } finally {
            operation.close();
        }
    }

    private StepNarrationResponseDTO toDto(String stepId, StepNarration record, NarrationScript script, Instant now) {
        StepNarrationResponseDTO.StepNarrationResponseDTOBuilder dto = StepNarrationResponseDTO.builder()
                .stepId(stepId)
                .narratable(script.narratable())
                // READY implies the same hash, so this is exactly the text the audio was generated from.
                .text(script.narratable() ? script.text() : null);
        if (!script.narratable() || record == null) {
            return dto.status(StepNarrationStatus.NOT_GENERATED.name()).build();
        }
        boolean sameText = script.hash().equals(record.getSourceTextHash());
        StepNarrationStatus status = switch (record.getStatus()) {
            case READY -> sameText ? StepNarrationStatus.READY : StepNarrationStatus.STALE;
            case GENERATING -> !isActive(record, now)
                    ? StepNarrationStatus.NOT_GENERATED // the generation died; the next ensure takes over
                    : sameText ? StepNarrationStatus.GENERATING : StepNarrationStatus.STALE;
            case FAILED -> sameText ? StepNarrationStatus.FAILED : StepNarrationStatus.STALE;
            default -> StepNarrationStatus.NOT_GENERATED;
        };
        dto.status(status.name());
        if (status == StepNarrationStatus.READY) {
            dto.audioUrl(record.getAudioUrl())
                    .mimeType(record.getMimeType())
                    .durationSeconds(record.getDurationMs() == null ? null : record.getDurationMs() / 1000.0)
                    .provider(record.getProvider())
                    .modelKey(record.getModelKey())
                    .voice(record.getVoice())
                    .languageCode(record.getLanguageCode())
                    .generatedAt(record.getGeneratedAt());
        } else if (status == StepNarrationStatus.FAILED) {
            dto.failureReason(UserFacingErrors.forJob(record.getFailureReason())).retryAfter(retryAfter(record));
        }
        return dto.build();
    }

    private Map<String, NarrationScript> loadScripts(Long recipeId, Long processId) {
        RecipeProcessVisualizationService.ProcessStepPreparation preparation;
        try {
            preparation = stepSource.prepareStepContexts(processId);
        } catch (RecipeProcessAiException e) {
            throw new NoSuchElementException("Process not found: " + processId);
        }
        Long owningRecipeId = preparation.process().getRecipeId();
        if (owningRecipeId == null || !owningRecipeId.equals(recipeId)) {
            throw new NoSuchElementException("Process " + processId + " not found in recipe " + recipeId);
        }
        Map<String, NarrationScript> scripts = new LinkedHashMap<>();
        for (RecipeProcessVisualizationService.StepContext step : preparation.steps()) {
            scripts.put(step.node().getId(), scriptBuilder.build(step.input()));
        }
        return scripts;
    }

    private static NarrationScript requireStep(Map<String, NarrationScript> scripts, String stepId) {
        NarrationScript script = scripts.get(stepId);
        if (script == null) {
            throw new NoSuchElementException("Step not found: " + stepId);
        }
        return script;
    }

    private RecipeTemplate requireViewable(Long recipeId, Long userId) {
        RecipeTemplate recipe = findRecipe(recipeId);
        if (recipe.getVisibility() == Visibility.PUBLIC || (userId != null && userId.equals(recipe.getCreatedBy()))) {
            return recipe;
        }
        throw new RecipeAccessDeniedException("You do not have permission to view this recipe");
    }

    private void requireOwned(Long recipeId, Long userId) {
        RecipeTemplate recipe = findRecipe(recipeId);
        if (userId == null || !userId.equals(recipe.getCreatedBy())) {
            throw new RecipeAccessDeniedException("You do not have permission to modify this recipe");
        }
    }

    private RecipeTemplate findRecipe(Long recipeId) {
        return recipeTemplateRepository.findById(recipeId)
                .orElseThrow(() -> new NoSuchElementException("Recipe not found: " + recipeId));
    }

    private static boolean isActive(StepNarration record, Instant now) {
        return record.getStatus() == StepNarrationStatus.GENERATING
                && record.getLeaseUntil() != null
                && record.getLeaseUntil().isAfter(now);
    }

    private boolean inFailureBackoff(StepNarration record, Instant now) {
        Instant retryAfter = retryAfter(record);
        return record.getStatus() == StepNarrationStatus.FAILED && retryAfter != null && retryAfter.isAfter(now);
    }

    private Instant retryAfter(StepNarration record) {
        return record.getFailedAt() == null ? null : record.getFailedAt().plusMillis(properties.getFailedRetryAfterMs());
    }

    static String narrationKey(Long recipeId, String stepId) {
        return VisualizationKeyBuilder.build(String.valueOf(recipeId), stepId);
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private static String describeFailure(Exception e) {
        String message = e.getMessage();
        return StringUtils.hasText(message) ? e.getClass().getSimpleName() + ": " + message : e.getClass().getSimpleName();
    }
}
