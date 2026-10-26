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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
    private RecipeProcessVisualizationJobService service;

    @BeforeEach
    void setUp() {
        processRepository = mock(ProcessRepository.class);
        VisualizationAssetRepository assetRepository = mock(VisualizationAssetRepository.class);
        SequenceGeneratorService sequenceGeneratorService = mock(SequenceGeneratorService.class);
        aiClient = mock(AiClient.class);
        imageGenerationClient = mock(ImageGenerationClient.class);
        ImageStorageClient imageStorageClient = mock(ImageStorageClient.class);

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
                new RecipeStepVocabularyProvider());

        visualizationJobRepository = mock(VisualizationJobRepository.class);
        when(visualizationJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mongoTemplate = mock(MongoTemplate.class);

        RecipeProcessVisualizationPromptBuilder promptBuilder = new RecipeProcessVisualizationPromptBuilder();
        VisualizationPromptBatchService promptBatchService = new VisualizationPromptBatchService(
                recipeProcessVisualizationService, promptBuilder, queueService, clientResolver,
                new AiJsonResponseSupport(mock(AiResponseService.class)));

        service = new RecipeProcessVisualizationJobService(
                recipeProcessVisualizationService,
                visualizationJobRepository,
                mongoTemplate,
                new ThreadPoolTaskPool(2, "test-process-visualization"),
                new ThreadPoolTaskPool(1, "test-process-orchestrator"),
                promptBatchService);
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
