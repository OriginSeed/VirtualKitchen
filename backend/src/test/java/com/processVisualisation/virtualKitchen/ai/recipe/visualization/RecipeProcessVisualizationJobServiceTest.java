package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAsset;
import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAssetRepository;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifactService;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.codec.GeneratedImageCodec;
import com.processVisualisation.virtualKitchen.ai.platform.credit.AiCreditProperties;
import com.processVisualisation.virtualKitchen.ai.platform.credit.AiCreditTransactionRepository;
import com.processVisualisation.virtualKitchen.ai.platform.credit.CreditService;
import com.processVisualisation.virtualKitchen.ai.platform.dispatch.AiClientResolver;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiQueueProperties;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestJobRepository;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestProperties;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestQueueService;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelProperties;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelRegistry;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelTier;
import com.processVisualisation.virtualKitchen.ai.platform.routing.ModelSelectionService;
import com.processVisualisation.virtualKitchen.ai.provider.AiClient;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient;
import com.processVisualisation.virtualKitchen.ai.provider.ImageStorageClient;
import com.processVisualisation.virtualKitchen.ai.platform.response.AiJsonResponseSupport;
import com.processVisualisation.virtualKitchen.ai.platform.response.AiResponseService;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiRequest;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiResponse;
import com.processVisualisation.virtualKitchen.ai.recipe.RecipeProcessAiException;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.dto.VisualizationJobResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.model.VisualizationJob;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.model.VisualizationJobStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider;
import com.processVisualisation.virtualKitchen.common.concurrent.ThreadPoolTaskPool;
import com.processVisualisation.virtualKitchen.common.persistence.SequenceGeneratorService;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessNodeKind;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessType;
import com.processVisualisation.virtualKitchen.recipe.repository.ProcessRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecipeProcessVisualizationJobServiceTest {

    private ProcessRepository processRepository;
    private VisualizationJobRepository visualizationJobRepository;
    private MongoTemplate mongoTemplate;
    private ImageGenerationClient imageGenerationClient;
    private AiClient aiClient;
    private ImageStorageClient imageStorageClient;
    private RecipeProcessVisualizationJobService service;
    private VisualizationImageProperties imageProperties;
    private VisualizationSceneAnchorService sceneAnchorService;

    @BeforeEach
    void setUp() {
        processRepository = mock(ProcessRepository.class);
        VisualizationAssetRepository assetRepository = mock(VisualizationAssetRepository.class);
        SequenceGeneratorService sequenceGeneratorService = mock(SequenceGeneratorService.class);
        aiClient = mock(AiClient.class);
        imageGenerationClient = mock(ImageGenerationClient.class);
        imageStorageClient = mock(ImageStorageClient.class);

        AtomicLong idCounter = new AtomicLong(0);
        when(sequenceGeneratorService.generateSequence(anyString())).thenAnswer(inv -> idCounter.incrementAndGet());
        Map<String, VisualizationAsset> assetStore = new ConcurrentHashMap<>();
        when(assetRepository.findByVisualizationKey(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(assetStore.get(inv.<String>getArgument(0))));
        when(assetRepository.save(any())).thenAnswer(inv -> {
            VisualizationAsset saved = inv.getArgument(0);
            assetStore.put(saved.getVisualizationKey(), saved);
            return saved;
        });
        when(assetRepository.findByVisualizationKeyIn(any())).thenAnswer(inv -> inv.<java.util.Collection<String>>getArgument(0).stream()
                .map(assetStore::get).filter(java.util.Objects::nonNull).toList());
        when(assetRepository.findByVisualizationKeyStartingWith(anyString())).thenAnswer(inv -> assetStore.values().stream()
                .filter(asset -> asset.getVisualizationKey().startsWith(inv.getArgument(0))).toList());
        when(aiClient.chat(any())).thenReturn(AiResponse.builder().content("{\"imagePrompt\":\"p\"}").build());
        when(imageStorageClient.upload(any(), anyString(), anyString())).thenReturn("https://cdn.example/img.png");

        ModelDefinition textModel = model("test-text", AiCapability.TEXT_TO_TEXT, "testTextClient");
        ModelDefinition imageModel = model("test-image", AiCapability.TEXT_TO_IMAGE, "testImageClient");
        AiModelProperties modelProperties = new AiModelProperties();
        modelProperties.setModels(List.of(textModel, imageModel));
        modelProperties.setDefaultModel(Map.of(
                AiCapability.TEXT_TO_TEXT.name(), "test-text",
                AiCapability.TEXT_TO_IMAGE.name(), "test-image"));
        AiModelRegistry modelRegistry = new AiModelRegistry(modelProperties);

        CreditService creditService = new CreditService(
                mock(MongoTemplate.class), new AiCreditProperties(), mock(AiCreditTransactionRepository.class));
        ModelSelectionService modelSelectionService = new ModelSelectionService(modelRegistry, creditService);
        AiClientResolver clientResolver = new AiClientResolver(
                Map.of("testTextClient", aiClient), Map.of("testImageClient", imageGenerationClient));

        AiRequestJobRepository aiRequestJobRepository = mock(AiRequestJobRepository.class);
        when(aiRequestJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AiArtifactService artifactService = mock(AiArtifactService.class);
        when(artifactService.findReusable(any(), any())).thenReturn(Optional.empty());

        AiRequestQueueService queueService = new AiRequestQueueService(
                aiRequestJobRepository,
                modelSelectionService,
                creditService,
                new ThreadPoolTaskPool(2, "test-ai-text"),
                new AiQueueProperties(),
                new AiRequestProperties(),
                artifactService,
                modelRegistry);

        VisualizationImageArtifactConsumer imageArtifactConsumer = new VisualizationImageArtifactConsumer(
                imageStorageClient, assetRepository, mock(MongoTemplate.class));

        // These tests pin down image generation as it was before the Focused Cooking View: with the view switched
        // off, every prompt, reference and request must be exactly what it always was.
        imageProperties = new VisualizationImageProperties();
        imageProperties.setFocusedViewEnabled(false);
        RecipeProcessVisualizationService recipeProcessVisualizationService = new RecipeProcessVisualizationService(
                processRepository,
                assetRepository,
                sequenceGeneratorService,
                queueService,
                clientResolver,
                new RecipeProcessVisualizationPromptBuilder(),
                artifactService,
                new GeneratedImageCodec(),
                imageArtifactConsumer,
                new RecipeStepVocabularyProvider(),
                new VisualizationImageProfiles(imageProperties, new RecipeStepVocabularyProvider()));
        recipeProcessVisualizationService.setImageStorageClient(imageStorageClient);

        visualizationJobRepository = mock(VisualizationJobRepository.class);
        when(visualizationJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mongoTemplate = mock(MongoTemplate.class);

        RecipeProcessVisualizationPromptBuilder promptBuilder = new RecipeProcessVisualizationPromptBuilder();
        sceneAnchorService = new VisualizationSceneAnchorService(
                recipeProcessVisualizationService, imageStorageClient, true);
        VisualizationPromptBatchService promptBatchService = new VisualizationPromptBatchService(
                recipeProcessVisualizationService, promptBuilder, queueService, clientResolver,
                new AiJsonResponseSupport(mock(AiResponseService.class)), sceneAnchorService);

        service = new RecipeProcessVisualizationJobService(
                recipeProcessVisualizationService,
                visualizationJobRepository,
                mongoTemplate,
                new ThreadPoolTaskPool(2, "test-process-visualization"),
                new ThreadPoolTaskPool(1, "test-process-orchestrator"),
                promptBatchService,
                sceneAnchorService);
    }

    @Test
    void startJob_drawsEveryStepAgainstTheRecipesSceneAnchor() throws Exception {
        when(aiClient.chat(any())).thenReturn(AiResponse.builder().content("{\"styleGuide\":\"one kitchen\","
                + "\"dishContext\":\"Part of making Egg Curry\",\"scenePrompt\":\"empty steel kadai on granite\",\"prompts\":["
                + "{\"id\":\"m1\",\"imagePrompt\":\"prompt one\"},"
                + "{\"id\":\"m2\",\"imagePrompt\":\"prompt two\"}]}").build());
        when(imageGenerationClient.supportsReferenceImages()).thenReturn(true);
        // The anchor itself is drawn without a reference; every step with it.
        when(imageGenerationClient.generate(anyString()))
                .thenReturn(new ImageGenerationClient.GeneratedImage("image/png", new byte[]{9}));
        when(imageGenerationClient.generate(anyString(), anyList()))
                .thenReturn(new ImageGenerationClient.GeneratedImage("image/png", new byte[]{1, 2, 3}));
        when(imageStorageClient.download("https://cdn.example/img.png"))
                .thenReturn(new ImageStorageClient.StoredImage("image/png", new byte[]{9}));
        when(processRepository.findById(1L)).thenReturn(Optional.of(processWithSteps(1L, 2)));

        service.startJob(1L, 100L, 1L);

        verify(processRepository, timeout(5000)).setStepVisualization(eq(1L), eq("node-0"), any(), eq("prompt one"), any());
        verify(processRepository, timeout(5000)).setStepVisualization(eq(1L), eq("node-1"), any(), eq("prompt two"), any());
        verify(imageGenerationClient, times(1)).generate("empty steel kadai on granite");
        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ImageGenerationClient.ReferenceImage>> references = ArgumentCaptor.forClass(List.class);
        verify(imageGenerationClient, times(2)).generate(texts.capture(), references.capture());
        for (String text : texts.getAllValues()) {
            assertTrue(text.startsWith("Part of making Egg Curry\n" + RecipeProcessVisualizationPromptBuilder.REFERENCE_INSTRUCTION), text);
        }
        references.getAllValues().forEach(sent -> {
            assertEquals(1, sent.size());
            assertEquals(9, sent.get(0).data()[0], "the anchor image is the reference");
        });
        verify(aiClient, times(1)).chat(any());
    }

    @Test
    void startJob_providerWithoutImageInput_getsTheDishContextButNoReferenceInstruction() throws Exception {
        when(aiClient.chat(any())).thenReturn(AiResponse.builder().content("{\"styleGuide\":\"one kitchen\","
                + "\"dishContext\":\"Part of making Egg Curry\",\"scenePrompt\":\"empty kadai\",\"prompts\":["
                + "{\"id\":\"m1\",\"imagePrompt\":\"prompt one\"}]}").build());
        when(imageGenerationClient.generate(anyString()))
                .thenReturn(new ImageGenerationClient.GeneratedImage("image/png", new byte[]{1}));
        when(imageStorageClient.download(anyString())).thenReturn(new ImageStorageClient.StoredImage("image/png", new byte[]{9}));
        when(processRepository.findById(1L)).thenReturn(Optional.of(processWithSteps(1L, 1)));

        service.startJob(1L, 100L, 1L);

        verify(processRepository, timeout(5000)).setStepVisualization(eq(1L), eq("node-0"), any(), eq("prompt one"), any());
        verify(imageGenerationClient).generate("Part of making Egg Curry\nprompt one");
        verify(imageGenerationClient, never()).generate(anyString(), anyList());
    }

    @Test
    void startJob_anchorThatCannotBeRead_stillGeneratesEveryStep() throws Exception {
        when(aiClient.chat(any())).thenReturn(AiResponse.builder().content("{\"styleGuide\":\"one kitchen\","
                + "\"dishContext\":\"Part of making Egg Curry\",\"scenePrompt\":\"empty kadai\",\"prompts\":["
                + "{\"id\":\"m1\",\"imagePrompt\":\"prompt one\"}]}").build());
        when(imageGenerationClient.supportsReferenceImages()).thenReturn(true);
        when(imageGenerationClient.generate(anyString()))
                .thenReturn(new ImageGenerationClient.GeneratedImage("image/png", new byte[]{1}));
        when(imageStorageClient.download(anyString())).thenThrow(new RuntimeException("storage down"));
        when(processRepository.findById(1L)).thenReturn(Optional.of(processWithSteps(1L, 1)));

        service.startJob(1L, 100L, 1L);

        verify(processRepository, timeout(5000)).setStepVisualization(eq(1L), eq("node-0"), any(), eq("prompt one"), eq("https://cdn.example/img.png"));
        // No reference to send, so no reference instruction either.
        verify(imageGenerationClient).generate("Part of making Egg Curry\nprompt one");
    }

    @Test
    void startJob_writesEveryImagePromptInOneBatchCall_thenOnlyGeneratesImages() throws Exception {
        when(aiClient.chat(any())).thenAnswer(inv -> {
            AiRequest request = inv.getArgument(0);
            assertEquals("VISUALIZATION_PROMPT_BATCH", request.getOperation(), "no per-step prompt call expected");
            return AiResponse.builder().content("{\"styleGuide\":\"one kitchen\",\"prompts\":["
                    + "{\"id\":\"m1\",\"imagePrompt\":\"prompt one\"},"
                    + "{\"id\":\"m2\",\"imagePrompt\":\"prompt two\"},"
                    + "{\"id\":\"m3\",\"imagePrompt\":\"prompt three\"}]}").build();
        });
        when(imageGenerationClient.generate(anyString()))
                .thenReturn(new ImageGenerationClient.GeneratedImage("image/png", new byte[]{1, 2, 3}));
        when(processRepository.findById(1L)).thenReturn(Optional.of(processWithSteps(1L, 3)));

        service.startJob(1L, 100L, 1L);

        verify(processRepository, timeout(5000)).setStepVisualization(eq(1L), eq("node-0"), any(), eq("prompt one"), any());
        verify(processRepository, timeout(5000)).setStepVisualization(eq(1L), eq("node-1"), any(), eq("prompt two"), any());
        verify(processRepository, timeout(5000)).setStepVisualization(eq(1L), eq("node-2"), any(), eq("prompt three"), any());
        verify(aiClient, times(1)).chat(any());
        verify(imageGenerationClient, times(3)).generate(anyString());
    }

    @Test
    void startJob_happyPath_savesProcessExactlyOnceAndRecordsEveryStep() throws Exception {
        when(imageGenerationClient.generate(anyString()))
                .thenReturn(new ImageGenerationClient.GeneratedImage("image/png", new byte[]{1, 2, 3}));
        Process process = processWithSteps(1L, 3);
        when(processRepository.findById(1L)).thenReturn(Optional.of(process));

        VisualizationJobResponseDTO started = service.startJob(1L, 100L, 1L);
        assertEquals(3, started.getTotalSteps());
        assertEquals("QUEUED", started.getStatus());
        assertEquals(1L, started.getProcessId());

        // Only the reduce step writes the shared process document: one atomic in-place update per
        // step, never a whole-document save (which could overwrite an editor save made meanwhile).
        for (int i = 0; i < 3; i++) {
            verify(processRepository, timeout(5000).times(1)).setStepVisualization(eq(1L), eq("node-" + i), any(), any(), any());
        }
        verify(processRepository, never()).save(any());

        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, timeout(5000).atLeast(4))
                .updateFirst(any(Query.class), updateCaptor.capture(), eq(com.processVisualisation.virtualKitchen.ai.recipe.visualization.model.VisualizationJob.class));

        List<Update> updates = updateCaptor.getAllValues();
        long stepIncrementCount = updates.stream().filter(u -> incValue(u, "completedSteps") == 1).count();
        assertEquals(3, stepIncrementCount, "expected exactly one completedSteps increment per step");

        boolean sawCompleted = updates.stream().anyMatch(u -> statusValue(u) == VisualizationJobStatus.COMPLETED);
        assertTrue(sawCompleted);
    }

    @Test
    void startJob_partialFailure_stillSavesOnceAndMarksCompletedWithErrors() throws Exception {
        when(imageGenerationClient.generate(anyString()))
                .thenReturn(new ImageGenerationClient.GeneratedImage("image/png", new byte[]{1}))
                .thenThrow(new RuntimeException("image generation failed"))
                .thenReturn(new ImageGenerationClient.GeneratedImage("image/png", new byte[]{1}));

        Process process = processWithSteps(2L, 3);
        when(processRepository.findById(2L)).thenReturn(Optional.of(process));

        service.startJob(1L, 100L, 2L);

        verify(processRepository, timeout(5000).atLeastOnce()).setStepVisualization(eq(2L), anyString(), any(), any(), any());
        verify(processRepository, never()).save(any());

        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, timeout(5000).atLeast(4))
                .updateFirst(any(Query.class), updateCaptor.capture(), eq(com.processVisualisation.virtualKitchen.ai.recipe.visualization.model.VisualizationJob.class));

        boolean sawCompletedWithErrors = updateCaptor.getAllValues().stream()
                .anyMatch(u -> statusValue(u) == VisualizationJobStatus.COMPLETED_WITH_ERRORS);
        assertTrue(sawCompletedWithErrors);
    }

    @Test
    void startJob_keepsExistingImagesByDefault_andRedrawsThemWhenAskedToRegenerate() throws Exception {
        when(imageGenerationClient.generate(anyString()))
                .thenReturn(new ImageGenerationClient.GeneratedImage("image/png", new byte[]{1, 2, 3}));
        when(processRepository.findById(1L)).thenReturn(Optional.of(processWithSteps(1L, 2)));

        service.startJob(1L, 100L, 1L);
        verify(processRepository, timeout(5000).times(2)).setStepVisualization(eq(1L), anyString(), any(), any(), any());
        int drawnFirst = imageCalls();
        assertTrue(drawnFirst >= 2, "both steps drawn");

        // Default: the steps already have images, so nothing is drawn again.
        service.startJob(1L, 100L, 1L);
        verify(processRepository, timeout(5000).times(4)).setStepVisualization(eq(1L), anyString(), any(), any(), any());
        assertEquals(drawnFirst, imageCalls(), "existing images are kept");

        // Regenerate and replace: both steps are drawn again (the scene anchor is kept, so exactly 2 more).
        service.startJob(1L, 100L, 1L, true);
        verify(processRepository, timeout(5000).times(6)).setStepVisualization(eq(1L), anyString(), any(), any(), any());
        assertEquals(drawnFirst + 2, imageCalls());
    }

    private int imageCalls() {
        return (int) org.mockito.Mockito.mockingDetails(imageGenerationClient).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().startsWith("generate"))
                .count();
    }

    @Test
    void startJob_processNotFound_throwsSynchronouslyWithoutCreatingAJob() {
        when(processRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(RecipeProcessAiException.class, () -> service.startJob(1L, 100L, 999L));

        verify(visualizationJobRepository, never()).save(any());
        verify(visualizationJobRepository, never()).insert(any(VisualizationJob.class));
    }

    @Test
    void startJob_whileAlreadyRunningForProcess_joinsRunningJobWithoutStartingAnother() {
        VisualizationJob running = new VisualizationJob();
        running.setId("running");
        running.setRecipeId("100");
        running.setProcessId(1L);
        running.setUserId(1L);
        running.setStatus(VisualizationJobStatus.IN_PROGRESS);
        running.setTotalSteps(3);
        running.setCompletedSteps(1);
        when(visualizationJobRepository.findByActiveKey("viz:1")).thenReturn(Optional.of(running));

        VisualizationJobResponseDTO result = service.startJob(1L, 100L, 1L);

        assertTrue(result.isReused());
        assertEquals("running", result.getJobId());
        assertEquals(1, result.getCompletedSteps());
        verify(visualizationJobRepository, never()).insert(any(VisualizationJob.class));
        verify(processRepository, never()).findById(any());
    }

    @Test
    void startJob_losesConcurrentInsertRace_joinsTheWinner() {
        when(processRepository.findById(1L)).thenReturn(Optional.of(processWithSteps(1L, 2)));
        VisualizationJob winner = new VisualizationJob();
        winner.setId("winner");
        winner.setStatus(VisualizationJobStatus.QUEUED);
        when(visualizationJobRepository.findByActiveKey("viz:1")).thenReturn(Optional.empty(), Optional.of(winner));
        when(visualizationJobRepository.insert(any(VisualizationJob.class))).thenThrow(new DuplicateKeyException("activeKey"));

        VisualizationJobResponseDTO result = service.startJob(1L, 100L, 1L);

        assertTrue(result.isReused());
        assertEquals("winner", result.getJobId());
    }

    @Test
    void startJob_processOfAnotherRecipe_isNotFound() {
        Process process = processWithSteps(1L, 1);
        process.setRecipeId(555L);
        when(processRepository.findById(1L)).thenReturn(Optional.of(process));

        assertThrows(NoSuchElementException.class, () -> service.startJob(1L, 100L, 1L));
        verify(visualizationJobRepository, never()).insert(any(VisualizationJob.class));
    }

    @Test
    void getJobStatus_jobOfAnotherUserRecipeOrProcess_isNotFound() {
        VisualizationJob job = new VisualizationJob();
        job.setId("j1");
        job.setRecipeId("100");
        job.setProcessId(1L);
        job.setUserId(1L);
        job.setStatus(VisualizationJobStatus.IN_PROGRESS);
        when(visualizationJobRepository.findById("j1")).thenReturn(Optional.of(job));

        assertEquals("j1", service.getJobStatus(1L, 100L, 1L, "j1").getJobId());
        assertThrows(NoSuchElementException.class, () -> service.getJobStatus(2L, 100L, 1L, "j1"));
        assertThrows(NoSuchElementException.class, () -> service.getJobStatus(1L, 101L, 1L, "j1"));
        assertThrows(NoSuchElementException.class, () -> service.getJobStatus(1L, 100L, 2L, "j1"));
    }

    @Test
    void startJob_onCompletion_clearsActiveKeySoANewJobCanStart() throws Exception {
        when(imageGenerationClient.generate(anyString()))
                .thenReturn(new ImageGenerationClient.GeneratedImage("image/png", new byte[]{1}));
        when(processRepository.findById(1L)).thenReturn(Optional.of(processWithSteps(1L, 1)));

        service.startJob(1L, 100L, 1L);

        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, timeout(5000).atLeast(3))
                .updateFirst(any(Query.class), updateCaptor.capture(), eq(VisualizationJob.class));
        Update completed = updateCaptor.getAllValues().stream()
                .filter(u -> statusValue(u) == VisualizationJobStatus.COMPLETED)
                .findFirst().orElseThrow();
        Document unset = (Document) completed.getUpdateObject().get("$unset");
        assertTrue(unset != null && unset.containsKey("activeKey"));
    }

    /**
     * Edits the user saved while the job ran must survive: the final write only sets each step's
     * visualization fields in place, never re-saving a copy of the process read at any point.
     */
    @Test
    void startJob_finalWrite_onlyTouchesEachStepsVisualizationFields() throws Exception {
        when(imageGenerationClient.generate(anyString()))
                .thenReturn(new ImageGenerationClient.GeneratedImage("image/png", new byte[]{1}));
        Process atStart = processWithSteps(4L, 1);
        when(processRepository.findById(4L)).thenReturn(Optional.of(atStart));

        service.startJob(1L, 100L, 4L);

        verify(processRepository, timeout(5000).times(1))
                .setStepVisualization(eq(4L), eq("node-0"), any(), any(), eq("https://cdn.example/img.png"));
        verify(processRepository, never()).save(any());
    }

    /**
     * Visualizing a MAIN process must only touch its own steps and its own document — a step's
     * Action On referencing a SUBPROCESS must never cause that subprocess's own document to be
     * loaded (beyond resolving its name for prompt context) or saved.
     */
    @Test
    void startJob_stepReferencingSubprocess_neverTouchesTheSubprocessDocument() throws Exception {
        when(imageGenerationClient.generate(anyString()))
                .thenReturn(new ImageGenerationClient.GeneratedImage("image/png", new byte[]{1}));

        Process subprocess = new Process();
        subprocess.setId(50L);
        subprocess.setName("Marinate Chicken");
        subprocess.setType(ProcessType.SUBPROCESS);
        when(processRepository.findAllById(any())).thenReturn(List.of(subprocess));

        Process main = new Process();
        main.setId(3L);
        main.setType(ProcessType.MAIN);
        Map<String, Object> stepFields = new LinkedHashMap<>();
        stepFields.put("action", "cook");
        stepFields.put("actionDescription", "Cook it");
        stepFields.put("expectedOutput", "Cooked");
        Map<String, Object> actionOn = new LinkedHashMap<>();
        actionOn.put("ingredients", List.of());
        actionOn.put("processes", List.of(Map.of("processId", 50)));
        stepFields.put("actionOn", actionOn);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("step", stepFields);
        Process.ProcessNode step = new Process.ProcessNode();
        step.setId("step-a");
        step.setKind(ProcessNodeKind.STEP);
        step.setData(data);
        main.setNodes(new ArrayList<>(List.of(step)));
        main.setEdges(new ArrayList<>());
        when(processRepository.findById(3L)).thenReturn(Optional.of(main));

        service.startJob(1L, 100L, 3L);

        verify(processRepository, timeout(5000).times(1)).setStepVisualization(eq(3L), eq("step-a"), any(), any(), any());
        verify(processRepository, never()).setStepVisualization(eq(50L), anyString(), any(), any(), any());
        verify(processRepository, never()).save(any());
        verify(processRepository, never()).findById(50L);
    }

    // --- Focused Cooking View: the six-step tomato recipe ------------------------------------------------

    private static final String TOMATO_ANSWER = "{\"dishContext\":\"Part of making Tomato Masala\","
            + "\"scene\":{\"vessel\":\"a steel kadai\",\"stove\":\"a black gas stove\",\"surface\":\"a granite countertop\","
            + "\"cuttingBoard\":\"a wooden cutting board\",\"prepBowl\":\"a steel bowl\",\"servingDish\":\"a white plate\"},"
            + "\"prompts\":["
            + "{\"id\":\"m1\",\"focus\":\"CUTTING_BOARD\",\"state\":\"STEP1 half an onion in small dice\",\"action\":\"a knife chopping\"},"
            + "{\"id\":\"m2\",\"focus\":\"COOKING_VESSEL\",\"state\":\"STEP2 oil shimmering in the pan\",\"action\":\"oil being poured\"},"
            + "{\"id\":\"m3\",\"focus\":\"COOKING_VESSEL\",\"state\":\"STEP3 raw chopped onions in oil\",\"action\":\"onions being tipped in\"},"
            + "{\"id\":\"m4\",\"focus\":\"COOKING_VESSEL\",\"state\":\"STEP4 softened onions in oil\",\"action\":\"tomatoes being tipped in\"},"
            + "{\"id\":\"m5\",\"focus\":\"COOKING_VESSEL\",\"state\":\"STEP5 onions and tomatoes with turmeric\",\"action\":\"a spatula stirring\"},"
            + "{\"id\":\"m6\",\"focus\":\"COOKING_VESSEL\",\"state\":\"STEP6 a soft thick tomato mixture\",\"action\":\"gentle bubbling\"}]}";

    /** Every upload gets its own URL, and reading a URL back returns its number, so references can be told apart. */
    private void givenFocusedTomatoRecipe() throws Exception {
        imageProperties.setFocusedViewEnabled(true);
        when(aiClient.chat(any())).thenReturn(AiResponse.builder().content(TOMATO_ANSWER).build());
        AtomicLong uploads = new AtomicLong();
        when(imageStorageClient.upload(any(), anyString(), anyString()))
                .thenAnswer(inv -> "https://cdn.example/" + uploads.incrementAndGet() + ".png");
        when(imageStorageClient.download(anyString())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            byte number = Byte.parseByte(url.substring(url.lastIndexOf('/') + 1, url.lastIndexOf('.')));
            return new ImageStorageClient.StoredImage("image/png", new byte[]{number});
        });
        // The scene anchor is a plain generation; focused steps carry settings (the recipe's seed).
        when(imageGenerationClient.generate(anyString()))
                .thenReturn(new ImageGenerationClient.GeneratedImage("image/png", new byte[]{9}));
        when(imageGenerationClient.generate(anyString(), anyList(), any()))
                .thenReturn(new ImageGenerationClient.GeneratedImage("image/png", new byte[]{1, 2, 3}));
        when(processRepository.findById(1L)).thenReturn(Optional.of(
                processWithActions(1L, "chop", "add", "add", "add", "stir", "cook")));
    }

    private record FocusedCall(String text, List<ImageGenerationClient.ReferenceImage> references,
                               ImageGenerationClient.ImageGenerationSettings settings) {
    }

    /** The focused image requests in the order they were made, once all six steps are attached. */
    private List<FocusedCall> focusedCalls(int expected) throws Exception {
        verify(processRepository, timeout(10000).times(6)).setStepVisualization(eq(1L), anyString(), any(), any(), any());
        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ImageGenerationClient.ReferenceImage>> references = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<ImageGenerationClient.ImageGenerationSettings> settings =
                ArgumentCaptor.forClass(ImageGenerationClient.ImageGenerationSettings.class);
        verify(imageGenerationClient, times(expected)).generate(texts.capture(), references.capture(), settings.capture());
        List<FocusedCall> calls = new ArrayList<>();
        for (int i = 0; i < expected; i++) {
            calls.add(new FocusedCall(texts.getAllValues().get(i), references.getAllValues().get(i), settings.getAllValues().get(i)));
        }
        return calls;
    }

    private static FocusedCall callFor(List<FocusedCall> calls, String marker) {
        return calls.stream().filter(call -> call.text().contains(marker)).findFirst().orElseThrow();
    }

    @Test
    void focusedView_drawsCookingStepsInOrder_eachAgainstTheAnchorAndThePreviousStep() throws Exception {
        givenFocusedTomatoRecipe();
        when(imageGenerationClient.supportsReferenceImages()).thenReturn(true);
        when(imageGenerationClient.maxReferenceImages()).thenReturn(2);

        service.startJob(1L, 100L, 1L);
        List<FocusedCall> calls = focusedCalls(6);

        // The anchor is the empty kadai, drawn once, uploaded first (so it reads back as image 1).
        ArgumentCaptor<String> anchorPrompt = ArgumentCaptor.forClass(String.class);
        verify(imageGenerationClient, times(1)).generate(anchorPrompt.capture());
        assertTrue(anchorPrompt.getValue().contains("A steel kadai on a black gas stove fills most of the frame"), anchorPrompt.getValue());
        assertTrue(anchorPrompt.getValue().contains("absolutely no food"));

        // Chopping looks at the board: no pan, and the pan anchor is not its reference.
        FocusedCall chop = callFor(calls, "STEP1");
        assertTrue(chop.text().contains("A wooden cutting board on a granite countertop fills most of the frame."), chop.text());
        assertFalse(chop.text().contains("kadai"), chop.text());
        assertTrue(chop.references().isEmpty());

        // The first cooking step is drawn against the anchor alone.
        FocusedCall oil = callFor(calls, "STEP2");
        assertEquals(1, oil.references().size());
        assertEquals(1, oil.references().get(0).data()[0], "the anchor");
        assertTrue(oil.text().endsWith(RecipeProcessVisualizationPromptBuilder.REFERENCE_INSTRUCTION), oil.text());

        // Every later cooking step continues from the one before it, in cooking order.
        List<String> order = calls.stream().map(FocusedCall::text).filter(text -> !text.contains("STEP1")).toList();
        String framing = oil.text().split("\n")[0];
        for (int step = 3; step <= 6; step++) {
            FocusedCall call = callFor(calls, "STEP" + step);
            assertEquals(order.indexOf(callFor(calls, "STEP" + (step - 1)).text()) + 1, order.indexOf(call.text()),
                    "step " + step + " runs right after step " + (step - 1));
            assertEquals(2, call.references().size(), "anchor and previous step");
            assertEquals(1, call.references().get(0).data()[0], "the anchor comes first");
            assertTrue(call.references().get(1).data()[0] > 1, "then the previous step's stored image");
            assertTrue(call.text().endsWith(RecipeProcessVisualizationPromptBuilder.ANCHOR_AND_PREVIOUS_INSTRUCTION), call.text());
            assertEquals(framing, call.text().split("\n")[0], "the same pan, stove and camera in every cooking step");
        }
        assertTrue(framing.contains("A steel kadai on a black gas stove"), framing);
        // Consecutive steps reference different images: each one's own predecessor.
        assertEquals(4, calls.stream().filter(call -> call.references().size() == 2)
                .map(call -> call.references().get(1).data()[0]).distinct().count());
        // One seed for the whole recipe.
        assertEquals(1, calls.stream().map(call -> call.settings().seed()).distinct().count());
        assertTrue(calls.get(0).settings().seed() != null);
        verify(aiClient, times(1)).chat(any());
    }

    @Test
    void focusedView_promptOnlyProvider_getsTheSameSceneTextAndNoReferences() throws Exception {
        givenFocusedTomatoRecipe();

        service.startJob(1L, 100L, 1L);
        List<FocusedCall> calls = focusedCalls(6);

        for (FocusedCall call : calls) {
            assertTrue(call.references().isEmpty());
            assertFalse(call.text().contains("reference image"), call.text());
            assertTrue(call.text().contains("Context: Part of making Tomato Masala"), call.text());
        }
        assertEquals(1, calls.stream().filter(call -> !call.text().contains("STEP1"))
                .map(call -> call.text().split("\n")[0]).distinct().count(), "one framing for all cooking steps");
    }

    @Test
    void focusedView_referenceChainSwitchedOff_usesTheAnchorAlone() throws Exception {
        givenFocusedTomatoRecipe();
        imageProperties.setReferenceChainEnabled(false);
        when(imageGenerationClient.supportsReferenceImages()).thenReturn(true);
        when(imageGenerationClient.maxReferenceImages()).thenReturn(2);

        service.startJob(1L, 100L, 1L);
        List<FocusedCall> calls = focusedCalls(6);

        for (int step = 2; step <= 6; step++) {
            FocusedCall call = callFor(calls, "STEP" + step);
            assertEquals(1, call.references().size());
            assertEquals(1, call.references().get(0).data()[0], "the anchor");
        }
    }

    @Test
    void focusedView_providerThatTakesOneReference_getsThePreviousStepRatherThanTheAnchor() throws Exception {
        givenFocusedTomatoRecipe();
        when(imageGenerationClient.supportsReferenceImages()).thenReturn(true);
        when(imageGenerationClient.maxReferenceImages()).thenReturn(1);

        service.startJob(1L, 100L, 1L);
        List<FocusedCall> calls = focusedCalls(6);

        assertEquals(1, callFor(calls, "STEP2").references().get(0).data()[0], "the first cooking step has only the anchor");
        FocusedCall tomatoes = callFor(calls, "STEP4");
        assertEquals(1, tomatoes.references().size());
        assertTrue(tomatoes.references().get(0).data()[0] > 1, "the previous step");
        assertTrue(tomatoes.text().endsWith(RecipeProcessVisualizationPromptBuilder.PREVIOUS_INSTRUCTION), tomatoes.text());
    }

    @Test
    void focusedView_aRequestWithReferencesThatFails_isMadeAgainFromThePromptAlone() throws Exception {
        givenFocusedTomatoRecipe();
        when(imageGenerationClient.supportsReferenceImages()).thenReturn(true);
        when(imageGenerationClient.maxReferenceImages()).thenReturn(2);
        when(imageGenerationClient.generate(anyString(), anyList(), any())).thenAnswer(inv -> {
            List<?> references = inv.getArgument(1);
            if (!references.isEmpty()) {
                throw new com.processVisualisation.virtualKitchen.ai.provider.exception.AiInvalidResponseException(
                        "model does not accept image input");
            }
            return new ImageGenerationClient.GeneratedImage("image/png", new byte[]{1, 2, 3});
        });

        service.startJob(1L, 100L, 1L);

        // Every step still gets its image.
        verify(processRepository, timeout(15000).times(6))
                .setStepVisualization(eq(1L), anyString(), any(), any(), org.mockito.ArgumentMatchers.startsWith("https://cdn.example/"));
        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ImageGenerationClient.ReferenceImage>> references = ArgumentCaptor.forClass(List.class);
        verify(imageGenerationClient, org.mockito.Mockito.atLeast(6)).generate(texts.capture(), references.capture(), any());
        for (int i = 0; i < texts.getAllValues().size(); i++) {
            if (references.getAllValues().get(i).isEmpty()) {
                assertFalse(texts.getAllValues().get(i).contains("reference image"), "the retry does not mention a reference");
            }
        }
    }

    @Test
    void focusedView_sceneAnchorOffAndChainOn_continuesFromThePreviousStepAlone() throws Exception {
        givenFocusedTomatoRecipe();
        sceneAnchorService.setEnabled(false);
        when(imageGenerationClient.supportsReferenceImages()).thenReturn(true);
        when(imageGenerationClient.maxReferenceImages()).thenReturn(2);

        service.startJob(1L, 100L, 1L);
        List<FocusedCall> calls = focusedCalls(6);

        // No anchor image is drawn at all.
        verify(imageGenerationClient, never()).generate(anyString());
        // The first cooking step starts the chain from its prompt alone.
        FocusedCall oil = callFor(calls, "STEP2");
        assertTrue(oil.references().isEmpty());
        assertFalse(oil.text().contains("reference image"), oil.text());
        // Every later one is drawn against its predecessor only, still in cooking order.
        List<String> order = calls.stream().map(FocusedCall::text).filter(text -> !text.contains("STEP1")).toList();
        for (int step = 3; step <= 6; step++) {
            FocusedCall call = callFor(calls, "STEP" + step);
            assertEquals(step - 2, order.indexOf(call.text()), "cooking steps run one after another");
            assertEquals(1, call.references().size(), "the previous step only");
            assertTrue(call.text().endsWith(RecipeProcessVisualizationPromptBuilder.PREVIOUS_INSTRUCTION), call.text());
        }
        // The recipe's equipment is part of the prompt, so it survives the anchor being off.
        assertTrue(oil.text().contains("A steel kadai on a black gas stove"), oil.text());
    }

    @Test
    void focusedView_sceneAnchorOffAndChainOff_isPromptOnly_andRunsWithoutWaitingOnEarlierSteps() throws Exception {
        givenFocusedTomatoRecipe();
        sceneAnchorService.setEnabled(false);
        imageProperties.setReferenceChainEnabled(false);
        when(imageGenerationClient.supportsReferenceImages()).thenReturn(true);
        when(imageGenerationClient.maxReferenceImages()).thenReturn(2);

        service.startJob(1L, 100L, 1L);
        List<FocusedCall> calls = focusedCalls(6);

        verify(imageGenerationClient, never()).generate(anyString());
        verify(imageStorageClient, never()).download(anyString());
        for (FocusedCall call : calls) {
            assertTrue(call.references().isEmpty());
            assertFalse(call.text().contains("reference image"), call.text());
        }
    }

    @Test
    void focusedView_aStepThatFails_doesNotStopItsChain_theNextStepUsesTheAnchorAlone() throws Exception {
        givenFocusedTomatoRecipe();
        when(imageGenerationClient.supportsReferenceImages()).thenReturn(true);
        when(imageGenerationClient.maxReferenceImages()).thenReturn(2);
        when(imageGenerationClient.generate(anyString(), anyList(), any())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            if (text.contains("STEP3")) {
                throw new com.processVisualisation.virtualKitchen.ai.provider.exception.AiInvalidResponseException("no image");
            }
            return new ImageGenerationClient.GeneratedImage("image/png", new byte[]{1, 2, 3});
        });

        service.startJob(1L, 100L, 1L);

        // Five steps get an image; the failed one is attached without a URL.
        verify(processRepository, timeout(15000).times(5))
                .setStepVisualization(eq(1L), anyString(), any(), any(), org.mockito.ArgumentMatchers.startsWith("https://cdn.example/"));
        verify(processRepository, timeout(15000)).setStepVisualization(eq(1L), eq("node-2"), any(), any(), org.mockito.ArgumentMatchers.isNull());
        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ImageGenerationClient.ReferenceImage>> references = ArgumentCaptor.forClass(List.class);
        verify(imageGenerationClient, org.mockito.Mockito.atLeast(6)).generate(texts.capture(), references.capture(), any());
        for (int i = 0; i < texts.getAllValues().size(); i++) {
            String text = texts.getAllValues().get(i);
            if (text.contains("STEP4")) {
                assertEquals(1, references.getAllValues().get(i).size(), "the step after the failure has only the anchor");
                assertEquals(1, references.getAllValues().get(i).get(0).data()[0]);
            }
            if (text.contains("STEP5")) {
                assertEquals(2, references.getAllValues().get(i).size(), "and the chain picks up again after it");
            }
        }
    }

    // --- Preparation steps sharing one panel image, and look-alike steps ------------------------------

    /** The focused batch answer for a five-step preparation; the last step only rests, like the one before it. */
    private static final String MARINADE_ANSWER = "{\"prompts\":["
            + "{\"id\":\"p1\",\"focus\":\"CUTTING_BOARD\",\"state\":\"PREP1 raw chicken\",\"action\":\"a knife cutting\",\"sameAsPrevious\":false},"
            + "{\"id\":\"p2\",\"focus\":\"PREP_BOWL\",\"state\":\"PREP2 chicken pieces\",\"action\":\"yogurt poured\",\"sameAsPrevious\":false},"
            + "{\"id\":\"p3\",\"focus\":\"PREP_BOWL\",\"state\":\"PREP3 chicken in yogurt\",\"action\":\"spices added\",\"sameAsPrevious\":false},"
            + "{\"id\":\"p4\",\"focus\":\"PREP_BOWL\",\"state\":\"PREP4 coated chicken\",\"action\":\"a spoon mixing\",\"sameAsPrevious\":false},"
            + "{\"id\":\"p5\",\"focus\":\"PREP_BOWL\",\"state\":\"PREP5 coated chicken\",\"action\":\"\",\"sameAsPrevious\":true}]}";

    /** A 2x2 panel image with a plain white gutter cross, as a provider would return it. */
    private static byte[] panelImage() throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 64, 64);
        g.setColor(new java.awt.Color(120, 60, 20));
        g.fillRect(0, 0, 30, 30);
        g.fillRect(34, 0, 30, 30);
        g.fillRect(0, 34, 30, 30);
        g.fillRect(34, 34, 30, 30);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private Map<String, String> givenMarinadePreparation(int stepsPerImage, boolean skipLookAlike) throws Exception {
        imageProperties.setFocusedViewEnabled(true);
        imageProperties.setPreparationStepsPerImage(stepsPerImage);
        imageProperties.setSkipLookAlikeSteps(skipLookAlike);
        when(aiClient.chat(any())).thenReturn(AiResponse.builder().content(MARINADE_ANSWER).build());
        AtomicLong uploads = new AtomicLong();
        Map<String, String> uploadedPaths = new ConcurrentHashMap<>();
        when(imageStorageClient.upload(any(), anyString(), anyString())).thenAnswer(inv -> {
            String url = "https://cdn.example/" + uploads.incrementAndGet() + ".png";
            uploadedPaths.put(url, inv.getArgument(2));
            return url;
        });
        byte[] panels = panelImage();
        when(imageStorageClient.download(anyString())).thenReturn(new ImageStorageClient.StoredImage("image/png", panels));
        when(imageGenerationClient.generate(anyString()))
                .thenReturn(new ImageGenerationClient.GeneratedImage("image/png", panels));
        when(imageGenerationClient.generate(anyString(), anyList(), any()))
                .thenReturn(new ImageGenerationClient.GeneratedImage("image/png", panels));
        Process preparation = processWithSteps(3L, 5);
        preparation.setType(ProcessType.SUBPROCESS);
        List<Process.ProcessEdge> edges = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Process.ProcessEdge edge = new Process.ProcessEdge();
            edge.setId("e" + i);
            edge.setSource("node-" + i);
            edge.setTarget("node-" + (i + 1));
            edges.add(edge);
        }
        preparation.setEdges(edges);
        when(processRepository.findById(3L)).thenReturn(Optional.of(preparation));
        return uploadedPaths;
    }

    /** The image URL attached to each step, once all five are attached. */
    private Map<String, String> attachedUrls() {
        ArgumentCaptor<String> nodes = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
        verify(processRepository, timeout(10000).times(5)).setStepVisualization(eq(3L), nodes.capture(), any(), any(), urls.capture());
        Map<String, String> byNode = new LinkedHashMap<>();
        for (int i = 0; i < nodes.getAllValues().size(); i++) {
            byNode.put(nodes.getAllValues().get(i), urls.getAllValues().get(i));
        }
        return byNode;
    }

    @Test
    void preparation_fourStepsShareOnePanelImage_cutIntoEachStepsOwnImage_andALookAlikeStepReusesTheOneBefore() throws Exception {
        Map<String, String> uploadedPaths = givenMarinadePreparation(4, true);

        service.startJob(1L, 100L, 3L);
        Map<String, String> urls = attachedUrls();

        // One image call for five steps: steps 1-4 as panels, step 5 copies step 4.
        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(imageGenerationClient, times(1)).generate(texts.capture(), anyList(), any());
        String text = texts.getValue();
        assertTrue(text.contains("4 equal square panels in a 2x2 grid"), text);
        assertTrue(text.contains("Top-left panel: A wooden cutting board"), text);
        assertTrue(text.indexOf("PREP1") < text.indexOf("PREP2") && text.indexOf("PREP3") < text.indexOf("PREP4"), text);
        assertFalse(text.contains("PREP5"), text);

        // The panel image is stored once, then each panel as its own step image.
        assertEquals(5, uploadedPaths.size(), "the panel image plus four cut panels");
        assertEquals(4, java.util.stream.IntStream.range(0, 4).mapToObj(i -> urls.get("node-" + i)).distinct().count());
        for (int i = 0; i < 4; i++) {
            assertTrue(uploadedPaths.get(urls.get("node-" + i)).startsWith("visualizations/100::node-" + i + "/"),
                    uploadedPaths.get(urls.get("node-" + i)));
        }
        assertEquals(urls.get("node-3"), urls.get("node-4"), "the resting step shows the image of the step before");
    }

    @Test
    void preparation_onePerImage_drawsEveryStepOnItsOwn() throws Exception {
        givenMarinadePreparation(1, true);

        service.startJob(1L, 100L, 3L);
        Map<String, String> urls = attachedUrls();

        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(imageGenerationClient, times(4)).generate(texts.capture(), anyList(), any());
        texts.getAllValues().forEach(text -> assertFalse(text.contains("panels"), text));
        assertEquals(urls.get("node-3"), urls.get("node-4"));
    }

    @Test
    void preparation_lookAlikeSkippingOff_drawsTheRestingStepToo() throws Exception {
        givenMarinadePreparation(4, false);

        service.startJob(1L, 100L, 3L);
        Map<String, String> urls = attachedUrls();

        // Four steps as one panel image, the fifth on its own.
        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(imageGenerationClient, times(2)).generate(texts.capture(), anyList(), any());
        assertEquals(1, texts.getAllValues().stream().filter(text -> text.contains("2x2 grid")).count());
        assertTrue(texts.getAllValues().stream().anyMatch(text -> text.contains("PREP5") && !text.contains("panel")));
        assertEquals(5, urls.values().stream().distinct().count());
    }

    private Process processWithActions(Long id, String... actions) {
        Process process = processWithSteps(id, actions.length);
        for (int i = 0; i < actions.length; i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> step = (Map<String, Object>) process.getNodes().get(i).getData().get("step");
            step.put("action", actions[i]);
        }
        return process;
    }

    private Process processWithSteps(Long id, int count) {
        Process process = new Process();
        process.setId(id);
        process.setType(ProcessType.MAIN);
        List<Process.ProcessNode> nodes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> stepFields = new LinkedHashMap<>();
            stepFields.put("action", "cut");
            stepFields.put("actionDescription", "Step " + i);
            stepFields.put("expectedOutput", "Result " + i);
            Map<String, Object> actionOn = new LinkedHashMap<>();
            actionOn.put("ingredients", List.of());
            actionOn.put("processes", List.of());
            stepFields.put("actionOn", actionOn);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("step", stepFields);

            Process.ProcessNode node = new Process.ProcessNode();
            node.setId("node-" + i);
            node.setKind(ProcessNodeKind.STEP);
            node.setData(data);
            nodes.add(node);
        }
        process.setNodes(nodes);
        process.setEdges(new ArrayList<>());
        return process;
    }

    private int incValue(Update update, String field) {
        Document updateObject = update.getUpdateObject();
        Document inc = (Document) updateObject.get("$inc");
        if (inc == null || !inc.containsKey(field)) return 0;
        return ((Number) inc.get(field)).intValue();
    }

    private VisualizationJobStatus statusValue(Update update) {
        Document updateObject = update.getUpdateObject();
        Document set = (Document) updateObject.get("$set");
        if (set == null || !set.containsKey("status")) return null;
        return (VisualizationJobStatus) set.get("status");
    }

    private ModelDefinition model(String key, AiCapability capability, String providerBean) {
        ModelDefinition definition = new ModelDefinition();
        definition.setKey(key);
        definition.setCapability(capability);
        definition.setTier(ModelTier.OPEN_SOURCE);
        definition.setProviderBean(providerBean);
        definition.setProviderModelId(key + "-model");
        definition.setEnabled(true);
        return definition;
    }
}
