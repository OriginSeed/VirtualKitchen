package com.processVisualisation.virtualKitchen.ai.recipe.narration;

import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifactService;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.codec.GeneratedAudioCodec;
import com.processVisualisation.virtualKitchen.ai.platform.credit.AiCreditProperties;
import com.processVisualisation.virtualKitchen.ai.platform.credit.AiCreditTransactionRepository;
import com.processVisualisation.virtualKitchen.ai.platform.credit.CreditService;
import com.processVisualisation.virtualKitchen.ai.platform.dispatch.AiClientResolver;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiQueueProperties;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestJob;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestJobRepository;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestProperties;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestQueueService;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelProperties;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelRegistry;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelTier;
import com.processVisualisation.virtualKitchen.ai.platform.routing.ModelSelectionService;
import com.processVisualisation.virtualKitchen.ai.provider.ProviderUsage;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiCommunicationException;
import com.processVisualisation.virtualKitchen.ai.provider.tts.AudioFormat;
import com.processVisualisation.virtualKitchen.ai.provider.tts.GeneratedAudio;
import com.processVisualisation.virtualKitchen.ai.provider.tts.TtsProvider;
import com.processVisualisation.virtualKitchen.ai.provider.tts.TtsRequest;
import com.processVisualisation.virtualKitchen.ai.provider.tts.WavAudio;
import com.processVisualisation.virtualKitchen.ai.recipe.RecipeProcessAiException;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.dto.StepNarrationResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.storage.NarrationAudioStorage;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationInput;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService;
import com.processVisualisation.virtualKitchen.common.concurrent.ThreadPoolTaskPool;
import com.processVisualisation.virtualKitchen.common.logging.LogCapture;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessNodeKind;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import com.processVisualisation.virtualKitchen.recipe.model.Visibility;
import com.processVisualisation.virtualKitchen.recipe.repository.RecipeTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Narration lifecycle end to end below the controller: provider selection through the real model
 * registry, resolver and queue, then cache reuse, stale detection, the duplicate-generation guard,
 * superseded generations, failure handling and access rules. Only the step source, recipe lookup
 * and persistence are faked.
 */
class StepNarrationServiceTest {

    private static final Long OWNER = 7L;
    private static final Long STRANGER = 99L;
    private static final Long RECIPE_ID = 10L;
    private static final Long PROCESS_ID = 20L;
    private static final String STEP_1 = "step-1";
    private static final String STEP_2 = "step-2";
    private static final Instant NOW = Instant.parse("2026-10-03T09:00:00Z");

    private final Map<String, RecipeProcessVisualizationInput> stepInputs = new LinkedHashMap<>();
    private final Map<String, TtsProvider> providers = new HashMap<>();
    private final Map<String, Function<TtsRequest, GeneratedAudio>> behaviour = new ConcurrentHashMap<>();
    private final Map<String, List<String>> spokenBy = new ConcurrentHashMap<>();
    private final List<AiRequestJob> savedAiRequests = new java.util.concurrent.CopyOnWriteArrayList<>();

    private RecipeTemplate recipe;
    private InMemoryStepNarrationStore store;
    private FakeAudioStorage storage;
    private ManualTaskPool pool;
    private MutableClock clock;
    private AiModelProperties modelProperties;
    private StepNarrationService service;

    @BeforeEach
    void setUp() {
        stepInputs.put(STEP_1, input("Add two tablespoons of oil to the pan"));
        stepInputs.put(STEP_2, input("Fry the onions until golden"));

        recipe = new RecipeTemplate();
        recipe.setId(RECIPE_ID);
        recipe.setCreatedBy(OWNER);
        recipe.setVisibility(Visibility.PRIVATE);

        registerProvider("geminiTtsProvider", "gemini");
        registerProvider("localTtsProvider", "local");

        modelProperties = new AiModelProperties();
        modelProperties.setModels(List.of(
                model("gemini-tts", "geminiTtsProvider"),
                model("local-tts", "localTtsProvider"),
                model("broken-tts", "noSuchTtsProvider")));
        modelProperties.setDefaultModel(new HashMap<>(Map.of(AiCapability.TEXT_TO_SPEECH.name(), "gemini-tts")));

        store = new InMemoryStepNarrationStore();
        storage = new FakeAudioStorage();
        pool = new ManualTaskPool();
        clock = new MutableClock(NOW);
        service = buildService();
    }

    // --- provider selection -------------------------------------------------------------------

    @Test
    void generatesWithTheProviderTheRegistryDefaultPointsAt() throws Exception {
        modelProperties.setDefaultModel(new HashMap<>(Map.of(AiCapability.TEXT_TO_SPEECH.name(), "local-tts")));
        service = buildService();

        service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);
        pool.runAll();

        StepNarrationResponseDTO ready = service.get(OWNER, RECIPE_ID, PROCESS_ID, STEP_1);
        assertEquals("READY", ready.getStatus());
        assertEquals("local", ready.getProvider());
        assertEquals("local-tts", ready.getModelKey());
        assertEquals(1, spoken("localTtsProvider").size());
        assertTrue(spoken("geminiTtsProvider").isEmpty(), "the non-selected provider must not be called");
    }

    @Test
    void modelPointingAtAnUnknownProviderBeanFailsTheNarrationCleanly() throws Exception {
        modelProperties.setDefaultModel(new HashMap<>(Map.of(AiCapability.TEXT_TO_SPEECH.name(), "broken-tts")));
        service = buildService();

        service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);
        pool.runAll();

        StepNarrationResponseDTO failed = service.get(OWNER, RECIPE_ID, PROCESS_ID, STEP_1);
        assertEquals("FAILED", failed.getStatus());
        assertTrue(failed.getFailureReason().contains("noSuchTtsProvider"), failed.getFailureReason());
        assertNull(failed.getAudioUrl());
    }

    @Test
    void unknownDefaultModelKeyFailsTheNarrationCleanly() throws Exception {
        modelProperties.setDefaultModel(new HashMap<>(Map.of(AiCapability.TEXT_TO_SPEECH.name(), "does-not-exist")));
        service = buildService();

        service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);
        pool.runAll();

        assertEquals("FAILED", service.get(OWNER, RECIPE_ID, PROCESS_ID, STEP_1).getStatus());
    }

    // --- lazy generation and reuse ------------------------------------------------------------

    @Test
    void missingNarrationIsGeneratedOnEnsureAndOnlyThen() throws Exception {
        assertEquals("NOT_GENERATED", service.get(OWNER, RECIPE_ID, PROCESS_ID, STEP_1).getStatus());
        assertEquals(List.of("NOT_GENERATED", "NOT_GENERATED"),
                service.list(OWNER, RECIPE_ID, PROCESS_ID).stream().map(StepNarrationResponseDTO::getStatus).toList());
        assertEquals(0, pool.queuedCount(), "reading must never generate");

        StepNarrationResponseDTO started = service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);
        assertEquals("GENERATING", started.getStatus());
        assertNull(started.getAudioUrl());

        pool.runAll();
        StepNarrationResponseDTO ready = service.get(OWNER, RECIPE_ID, PROCESS_ID, STEP_1);
        assertEquals("READY", ready.getStatus());
        assertNotNull(ready.getAudioUrl());
        assertEquals("audio/wav", ready.getMimeType());
        assertEquals(1.0, ready.getDurationSeconds());
        assertEquals("gemini", ready.getProvider());
        assertEquals("en-US", ready.getLanguageCode());
        assertEquals(List.of("Add two tablespoons of oil to the pan."), spoken("geminiTtsProvider"));
        assertEquals(spoken("geminiTtsProvider").get(0), ready.getText(), "the player shows exactly what was spoken");
        assertEquals("Add two tablespoons of oil to the pan.", started.getText(), "the script is known before the audio is");
    }

    @Test
    void batchKeepsReadyNarrationByDefault_andReplacesItWhenForced() throws Exception {
        service.ensureAll(OWNER, RECIPE_ID, PROCESS_ID, null);
        pool.runAll();
        int spokenFirst = spoken("geminiTtsProvider").size();
        assertEquals(2, spokenFirst);

        service.ensureAll(OWNER, RECIPE_ID, PROCESS_ID, null);
        pool.runAll();
        assertEquals(spokenFirst, spoken("geminiTtsProvider").size(), "READY narration is kept");

        List<StepNarrationResponseDTO> forced = service.ensureAll(OWNER, RECIPE_ID, PROCESS_ID, null, true);
        assertEquals(List.of("GENERATING", "GENERATING"), forced.stream().map(StepNarrationResponseDTO::getStatus).toList());
        pool.runAll();
        assertEquals(spokenFirst + 2, spoken("geminiTtsProvider").size(), "every step narrated again");
        assertEquals(List.of("READY", "READY"),
                service.list(OWNER, RECIPE_ID, PROCESS_ID).stream().map(StepNarrationResponseDTO::getStatus).toList());
    }

    @Test
    void sameTextReusesExistingNarrationWithoutCallingTheProvider() throws Exception {
        service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);
        pool.runAll();
        String firstUrl = service.get(OWNER, RECIPE_ID, PROCESS_ID, STEP_1).getAudioUrl();

        try (LogCapture logs = LogCapture.of(StepNarrationService.class)) {
            StepNarrationResponseDTO again = service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);
            assertEquals("READY", again.getStatus());
            assertEquals(firstUrl, again.getAudioUrl());
            assertEquals(1, logs.events("narration_cache_hit").size());
        }
        assertEquals(0, pool.queuedCount());
        assertEquals(1, spoken("geminiTtsProvider").size());
    }

    // --- stale detection ----------------------------------------------------------------------

    @Test
    void editedTextMakesNarrationStaleAndEnsureRegeneratesIt() throws Exception {
        service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);
        pool.runAll();
        String oldPath = storage.onlyPath();

        stepInputs.put(STEP_1, input("Add three tablespoons of oil to the pan"));

        StepNarrationResponseDTO stale = service.get(OWNER, RECIPE_ID, PROCESS_ID, STEP_1);
        assertEquals("STALE", stale.getStatus());
        assertNull(stale.getAudioUrl(), "stale audio must never be handed out");

        service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);
        pool.runAll();

        StepNarrationResponseDTO fresh = service.get(OWNER, RECIPE_ID, PROCESS_ID, STEP_1);
        assertEquals("READY", fresh.getStatus());
        assertEquals(List.of("Add two tablespoons of oil to the pan.", "Add three tablespoons of oil to the pan."),
                spoken("geminiTtsProvider"));
        assertFalse(storage.files.containsKey(oldPath), "the replaced audio is deleted");
        assertEquals(1, storage.files.size());
    }

    @Test
    void cosmeticallyDifferentButEquivalentTextStaysReady() throws Exception {
        service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);
        pool.runAll();

        stepInputs.put(STEP_1, input("  Add two tablespoons   of oil to the pan.  "));

        assertEquals("READY", service.get(OWNER, RECIPE_ID, PROCESS_ID, STEP_1).getStatus());
    }

    // --- duplicate-generation guard and superseded generations -------------------------------

    @Test
    void concurrentEnsuresForTheSameStepGenerateOnce() throws Exception {
        StepNarrationResponseDTO first = service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);
        StepNarrationResponseDTO second = service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);

        assertEquals("GENERATING", first.getStatus());
        assertEquals("GENERATING", second.getStatus());
        assertEquals(1, pool.queuedCount(), "the second request must join, not start another generation");

        pool.runAll();
        assertEquals(1, spoken("geminiTtsProvider").size());
    }

    @Test
    void expiredLeaseCanBeTakenOver() throws Exception {
        service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);
        assertEquals(1, pool.queuedCount());

        clock.advance(Duration.ofMinutes(10)); // the first generation "crashed" and never finished
        assertEquals("NOT_GENERATED", service.get(OWNER, RECIPE_ID, PROCESS_ID, STEP_1).getStatus());

        service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);
        assertEquals(2, pool.queuedCount());

        pool.run(1);
        assertEquals("READY", service.get(OWNER, RECIPE_ID, PROCESS_ID, STEP_1).getStatus());
    }

    @Test
    void supersededGenerationCannotOverwriteNewerNarration() throws Exception {
        service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);           // generation A, old text
        stepInputs.put(STEP_1, input("Add three tablespoons of oil to the pan"));
        service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);           // generation B, new text
        assertEquals(2, pool.queuedCount());

        pool.run(1);                                                          // B finishes first
        String newUrl = service.get(OWNER, RECIPE_ID, PROCESS_ID, STEP_1).getAudioUrl();
        pool.run(0);                                                          // A finishes late

        StepNarrationResponseDTO current = service.get(OWNER, RECIPE_ID, PROCESS_ID, STEP_1);
        assertEquals("READY", current.getStatus());
        assertEquals(newUrl, current.getAudioUrl(), "the late, superseded generation must not win");
        assertEquals(1, storage.files.size(), "the superseded audio is never kept");
    }

    // --- failures -----------------------------------------------------------------------------

    @Test
    void providerFailureMarksFailedAndBacksOffBeforeRetrying() throws Exception {
        behaviour.put("geminiTtsProvider", request -> {
            throw new AiCommunicationException("Gemini TTS rate limit or quota exceeded (429)");
        });

        service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);
        pool.runAll();

        StepNarrationResponseDTO failed = service.get(OWNER, RECIPE_ID, PROCESS_ID, STEP_1);
        assertEquals("FAILED", failed.getStatus());
        assertTrue(failed.getFailureReason().contains("429"));
        assertEquals(NOW.plusMillis(30_000), failed.getRetryAfter());
        int callsSoFar = spoken("geminiTtsProvider").size();

        assertEquals("FAILED", service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false).getStatus());
        assertEquals(0, pool.queuedCount(), "no retry inside the back-off window");

        behaviour.remove("geminiTtsProvider");
        clock.advance(Duration.ofSeconds(31));
        assertEquals("GENERATING", service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false).getStatus());
        pool.runAll();
        assertEquals("READY", service.get(OWNER, RECIPE_ID, PROCESS_ID, STEP_1).getStatus());
        assertEquals(callsSoFar + 1, spoken("geminiTtsProvider").size());
    }

    @Test
    void batchFailureOfOneStepDoesNotAffectTheOthers() throws Exception {
        service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_2, false);
        pool.runAll();
        String step2Url = service.get(OWNER, RECIPE_ID, PROCESS_ID, STEP_2).getAudioUrl();

        behaviour.put("geminiTtsProvider", request -> {
            if (request.text().contains("oil")) {
                throw new AiCommunicationException("boom");
            }
            return wav(request);
        });

        List<StepNarrationResponseDTO> batch = service.ensureAll(OWNER, RECIPE_ID, PROCESS_ID, null);
        assertEquals(List.of("GENERATING", "READY"), batch.stream().map(StepNarrationResponseDTO::getStatus).toList());
        pool.runAll();

        List<StepNarrationResponseDTO> after = service.list(OWNER, RECIPE_ID, PROCESS_ID);
        assertEquals("FAILED", after.get(0).getStatus());
        assertEquals("READY", after.get(1).getStatus());
        assertEquals(step2Url, after.get(1).getAudioUrl());
    }

    @Test
    void stepWithoutTextIsNotNarratable() {
        stepInputs.put(STEP_1, new RecipeProcessVisualizationInput("", List.of(), List.of(), "", "", "", "", "", null));

        StepNarrationResponseDTO result = service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);

        assertEquals("NOT_GENERATED", result.getStatus());
        assertFalse(result.isNarratable());
        assertEquals(0, pool.queuedCount());
    }

    @Test
    void forceRegeneratesReadyNarration() throws Exception {
        service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);
        pool.runAll();
        String firstUrl = service.get(OWNER, RECIPE_ID, PROCESS_ID, STEP_1).getAudioUrl();

        assertEquals("GENERATING", service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, true).getStatus());
        pool.runAll();

        StepNarrationResponseDTO regenerated = service.get(OWNER, RECIPE_ID, PROCESS_ID, STEP_1);
        assertNotEquals(firstUrl, regenerated.getAudioUrl());
        assertEquals(2, spoken("geminiTtsProvider").size());
    }

    @Test
    void deleteRemovesRecordAndAudio() throws Exception {
        service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);
        pool.runAll();

        service.delete(OWNER, RECIPE_ID, PROCESS_ID, STEP_1);

        assertEquals("NOT_GENERATED", service.get(OWNER, RECIPE_ID, PROCESS_ID, STEP_1).getStatus());
        assertTrue(storage.files.isEmpty());
    }

    // --- access and validation ----------------------------------------------------------------

    @Test
    void privateRecipeIsNotNarratedForOtherUsers() {
        assertThrows(RecipeAccessDeniedException.class, () -> service.list(STRANGER, RECIPE_ID, PROCESS_ID));
        assertThrows(RecipeAccessDeniedException.class, () -> service.ensure(STRANGER, RECIPE_ID, PROCESS_ID, STEP_1, false));
    }

    @Test
    void publicRecipeCanBeNarratedButOnlyTheOwnerMayForceOrDelete() {
        recipe.setVisibility(Visibility.PUBLIC);

        assertEquals("GENERATING", service.ensure(STRANGER, RECIPE_ID, PROCESS_ID, STEP_1, false).getStatus());
        assertThrows(RecipeAccessDeniedException.class, () -> service.ensure(STRANGER, RECIPE_ID, PROCESS_ID, STEP_1, true));
        assertThrows(RecipeAccessDeniedException.class, () -> service.delete(STRANGER, RECIPE_ID, PROCESS_ID, STEP_1));
    }

    @Test
    void unknownStepOrForeignProcessIsNotFound() {
        assertThrows(NoSuchElementException.class, () -> service.ensure(OWNER, RECIPE_ID, PROCESS_ID, "nope", false));
        assertThrows(NoSuchElementException.class, () -> service.list(OWNER, RECIPE_ID, 404L));
    }

    @Test
    void completedLineAndAiRequestRecordCarryModelTokensCostAndDuration() throws Exception {
        modelProperties.getModels().get(0).setInputUsdPerMillion(0.50);
        modelProperties.getModels().get(0).setOutputUsdPerMillion(10.00);
        service = buildService();
        behaviour.put("geminiTtsProvider", request -> {
            GeneratedAudio audio = wav(request);
            return new GeneratedAudio(audio.data(), audio.mimeType(), audio.format(), audio.durationMs(), audio.voice(),
                    audio.providerModelId(), new ProviderUsage(10L, 100L, null, 110L));
        });

        try (LogCapture logs = LogCapture.of(StepNarrationService.class);
             LogCapture queueLogs = LogCapture.of(AiRequestQueueService.class)) {
            service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);
            pool.runAll();

            String completed = logs.events("narration_generation_completed").get(0).getFormattedMessage();
            assertTrue(completed.contains("modelKey=gemini-tts"), completed);
            assertTrue(completed.contains("aiRequests=1 inputTokens=10 outputTokens=100 costUsd=0.001005"), completed);
            assertTrue(completed.contains("durationMs="), completed);

            String request = queueLogs.events("ai_request_completed").get(0).getFormattedMessage();
            assertTrue(request.contains("operation=step-narration capability=TEXT_TO_SPEECH modelKey=gemini-tts"), request);
            assertTrue(request.contains("inputTokens=10") && request.contains("outputTokens=100"), request);
            assertTrue(request.contains("costUsd=0.001005"), request);
        }

        AiRequestJob persisted = savedAiRequests.get(savedAiRequests.size() - 1);
        assertEquals("narration:" + RECIPE_ID + "::" + STEP_1, persisted.getOperationId());
        assertNotNull(persisted.getDurationMs());
        assertEquals(0.001005, persisted.getUsage().estimatedCostUsd(), 1e-12);
        assertEquals(10, persisted.getUsage().promptTokens());
    }

    @Test
    void narrationTextIsNeverLogged() throws Exception {
        String secretText = "Sprinkle the sentinel-spice-4711 over everything";
        stepInputs.put(STEP_1, input(secretText));

        try (LogCapture serviceLogs = LogCapture.of(StepNarrationService.class);
             LogCapture consumerLogs = LogCapture.of(NarrationAudioArtifactConsumer.class);
             LogCapture providerLogs = LogCapture.of("com.processVisualisation.virtualKitchen.ai.platform.dispatch.LoggingTtsProvider")) {
            service.ensure(OWNER, RECIPE_ID, PROCESS_ID, STEP_1, false);
            pool.runAll();

            String all = serviceLogs.allMessages() + consumerLogs.allMessages() + providerLogs.allMessages();
            assertFalse(all.contains("sentinel-spice-4711"), all);
            assertEquals(1, providerLogs.events("tts_synthesis_completed").size());
        }
    }

    // --- wiring -------------------------------------------------------------------------------

    private StepNarrationService buildService() {
        AiModelRegistry registry = new AiModelRegistry(modelProperties);
        CreditService creditService = new CreditService(
                mock(MongoTemplate.class), new AiCreditProperties(), mock(AiCreditTransactionRepository.class));
        AiRequestJobRepository jobRepository = mock(AiRequestJobRepository.class);
        when(jobRepository.save(any())).thenAnswer(inv -> {
            savedAiRequests.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        AiArtifactService artifactService = mock(AiArtifactService.class);
        when(artifactService.findReusable(any(), any())).thenReturn(Optional.empty());

        AiRequestProperties requestProperties = new AiRequestProperties();
        requestProperties.setMaxRetries(0);
        AiRequestQueueService queueService = new AiRequestQueueService(
                jobRepository,
                new ModelSelectionService(registry, creditService),
                creditService,
                new ThreadPoolTaskPool(1, "test-ai-text"),
                new AiQueueProperties(),
                requestProperties,
                artifactService,
                registry);
        AiClientResolver resolver = new AiClientResolver(Map.of(), Map.of(), providers);

        RecipeTemplateRepository recipes = mock(RecipeTemplateRepository.class);
        when(recipes.findById(RECIPE_ID)).thenAnswer(inv -> Optional.of(recipe));

        RecipeProcessVisualizationService stepSource = mock(RecipeProcessVisualizationService.class);
        when(stepSource.prepareStepContexts(any())).thenAnswer(inv -> {
            Long processId = inv.getArgument(0);
            if (!PROCESS_ID.equals(processId)) {
                throw new RecipeProcessAiException("Process not found: " + processId);
            }
            return preparation();
        });

        NarrationProperties properties = new NarrationProperties();
        NarrationAudioArtifactConsumer consumer = new NarrationAudioArtifactConsumer(
                storage, store, registry, resolver, properties, clock);

        return new StepNarrationService(recipes, stepSource, new NarrationScriptBuilder(properties), store, queueService,
                resolver, new GeneratedAudioCodec(), consumer, artifactService, storage, properties, pool, clock);
    }

    private RecipeProcessVisualizationService.ProcessStepPreparation preparation() {
        Process process = new Process();
        process.setId(PROCESS_ID);
        process.setRecipeId(RECIPE_ID);
        List<RecipeProcessVisualizationService.StepContext> steps = new ArrayList<>();
        stepInputs.forEach((stepId, input) -> {
            Process.ProcessNode node = new Process.ProcessNode();
            node.setId(stepId);
            node.setKind(ProcessNodeKind.STEP);
            steps.add(new RecipeProcessVisualizationService.StepContext(node, input));
        });
        return new RecipeProcessVisualizationService.ProcessStepPreparation(process, steps);
    }

    private void registerProvider(String beanName, String providerName) {
        providers.put(beanName, new TtsProvider() {
            @Override
            public String providerName() {
                return providerName;
            }

            @Override
            public GeneratedAudio synthesize(TtsRequest request) {
                spokenBy.computeIfAbsent(beanName, k -> new ArrayList<>()).add(request.text());
                return behaviour.getOrDefault(beanName, StepNarrationServiceTest::wav).apply(request);
            }
        });
    }

    private List<String> spoken(String beanName) {
        return spokenBy.getOrDefault(beanName, List.of());
    }

    /** One second of silent 8 kHz mono 16-bit audio. */
    private static GeneratedAudio wav(TtsRequest request) {
        byte[] data = WavAudio.fromPcm(new byte[16_000], 8000, 1, 16);
        return new GeneratedAudio(data, AudioFormat.WAV.mimeType(), AudioFormat.WAV, 1000L, "Kore", request.providerModelId());
    }

    private static RecipeProcessVisualizationInput input(String description) {
        return new RecipeProcessVisualizationInput("Add", List.of(), List.of(), description, "", "", "", "", null);
    }

    private static ModelDefinition model(String key, String providerBean) {
        ModelDefinition model = new ModelDefinition();
        model.setKey(key);
        model.setCapability(AiCapability.TEXT_TO_SPEECH);
        model.setTier(ModelTier.OPEN_SOURCE);
        model.setProviderBean(providerBean);
        model.setProviderModelId(key + "-model");
        model.setCreditCost(0);
        return model;
    }

    /** Keeps "stored" audio in a map; paths are unique per store, like the real backends. */
    private static final class FakeAudioStorage implements NarrationAudioStorage {
        private final Map<String, byte[]> files = new LinkedHashMap<>();
        private int counter;

        @Override
        public String backendName() {
            return "fake";
        }

        @Override
        public synchronized StoredAudio store(byte[] data, AudioFormat format, String narrationKey) {
            String path = "audio-" + (++counter) + "." + format.extension();
            files.put(path, data);
            return new StoredAudio("https://audio.example/" + path, "fake", path);
        }

        @Override
        public synchronized void delete(String storagePath) {
            files.remove(storagePath);
        }

        @Override
        public synchronized Optional<byte[]> read(String storagePath) {
            return Optional.ofNullable(files.get(storagePath));
        }

        String onlyPath() {
            Set<String> paths = files.keySet();
            assertEquals(1, paths.size());
            return paths.iterator().next();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
