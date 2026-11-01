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
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiResponse;
import com.processVisualisation.virtualKitchen.ai.recipe.RecipeProcessAiException;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider;
import com.processVisualisation.virtualKitchen.common.concurrent.ThreadPoolTaskPool;
import com.processVisualisation.virtualKitchen.common.persistence.SequenceGeneratorService;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessNodeKind;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessType;
import com.processVisualisation.virtualKitchen.recipe.repository.ProcessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the Process-model-specific behaviour {@code RecipeProcessVisualizationServiceArtifactTest}
 * doesn't: STEP-only filtering (CONDITION skipped), edge-based ordering, multi-ingredient Action On
 * extraction, subprocess name resolution, and previous-step continuity chaining.
 */
class RecipeProcessVisualizationServiceTest {

    private ProcessRepository processRepository;
    private RecipeProcessVisualizationService service;

    @BeforeEach
    void setUp() {
        processRepository = mock(ProcessRepository.class);
        VisualizationAssetRepository assetRepository = mock(VisualizationAssetRepository.class);
        SequenceGeneratorService sequenceGeneratorService = mock(SequenceGeneratorService.class);
        AiClient aiClient = mock(AiClient.class);
        ImageGenerationClient imageGenerationClient = mock(ImageGenerationClient.class);
        ImageStorageClient imageStorageClient = mock(ImageStorageClient.class);

        Map<String, VisualizationAsset> assetStore = new ConcurrentHashMap<>();
        AtomicLong idCounter = new AtomicLong();
        when(sequenceGeneratorService.generateSequence(anyString())).thenAnswer(inv -> idCounter.incrementAndGet());
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

        AiRequestJobRepository jobRepository = mock(AiRequestJobRepository.class);
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AiArtifactService artifactService = mock(AiArtifactService.class);
        when(artifactService.findReusable(any(), any())).thenReturn(Optional.empty());

        AiRequestQueueService queueService = new AiRequestQueueService(
                jobRepository,
                new ModelSelectionService(modelRegistry, creditService),
                creditService,
                new ThreadPoolTaskPool(2, "test-ai-text"),
                new AiQueueProperties(),
                new AiRequestProperties(),
                artifactService,
                modelRegistry);

        VisualizationImageArtifactConsumer consumer = new VisualizationImageArtifactConsumer(
                imageStorageClient, assetRepository, mock(MongoTemplate.class));

        service = new RecipeProcessVisualizationService(
                processRepository,
                assetRepository,
                sequenceGeneratorService,
                queueService,
                new AiClientResolver(Map.of("testTextClient", aiClient), Map.of("testImageClient", imageGenerationClient)),
                new RecipeProcessVisualizationPromptBuilder(),
                artifactService,
                new GeneratedImageCodec(),
                consumer,
                new RecipeStepVocabularyProvider(),
                new VisualizationImageProfiles(new VisualizationImageProperties(), new RecipeStepVocabularyProvider()));
    }

    @Test
    void prepareVisualTargets_givesAPreparationOneImagePerFlow_attachedToItsOutputStep() {
        Process preparation = new Process();
        preparation.setId(5L);
        preparation.setRecipeId(9L);
        preparation.setType(ProcessType.SUBPROCESS);
        Process.ProcessNode cut = stepNode("cut", "cut", List.of(ingredient("chicken", 500, "g", "bite-sized")), "Cut the chicken", "Cut Chicken");
        Process.ProcessNode marinate = stepNode("marinate", "marinate", List.of(ingredient("yogurt", 1, "cup", null)), "Coat in yogurt", "Marinated Chicken");
        Process.ProcessNode chop = stepNode("chop", "chop", List.of(ingredient("onion", 2, "piece", "chopped")), "Chop the onions", "Chopped Onions");
        preparation.setNodes(new ArrayList<>(List.of(cut, marinate, chop)));
        preparation.setEdges(new ArrayList<>(List.of(edge("cut", "marinate"))));
        when(processRepository.findById(5L)).thenReturn(Optional.of(preparation));

        RecipeProcessVisualizationService.VisualPreparation targets = service.prepareVisualTargets(5L);

        assertEquals(2, targets.targets().size(), "one image per flow, not per step");
        RecipeProcessVisualizationService.VisualTarget chicken = targets.targets().stream()
                .filter(target -> target.nodeId().equals("marinate")).findFirst().orElseThrow();
        assertEquals("Marinated Chicken", chicken.flow().outputName());
        assertEquals(List.of("Cut Chicken", "Marinated Chicken"), chicken.flow().steps().stream().map(RecipeProcessVisualizationInput::expectedOutput).toList());
        RecipeProcessVisualizationService.VisualTarget onions = targets.targets().stream()
                .filter(target -> target.nodeId().equals("chop")).findFirst().orElseThrow();
        assertNull(onions.flow().steps().get(0).previousExpectedOutput(), "an independent flow doesn't continue from another");
        assertEquals(List.of("marinate", "chop").stream().sorted().toList(),
                RecipeProcessVisualizationService.visualTargetNodeIds(preparation).stream().sorted().toList());
    }

    @Test
    void prepareVisualTargets_keepsOneImagePerStepForTheMainProcess() {
        Process main = new Process();
        main.setId(6L);
        main.setRecipeId(9L);
        main.setType(ProcessType.MAIN);
        main.setNodes(new ArrayList<>(List.of(
                stepNode("heat", "heat", List.of(), "Heat the oil", "Hot oil"),
                stepNode("fry", "fry", List.of(), "Fry", "Fried"))));
        main.setEdges(new ArrayList<>(List.of(edge("heat", "fry"))));
        when(processRepository.findById(6L)).thenReturn(Optional.of(main));

        RecipeProcessVisualizationService.VisualPreparation targets = service.prepareVisualTargets(6L);

        assertEquals(List.of("heat", "fry"), targets.targets().stream().map(RecipeProcessVisualizationService.VisualTarget::nodeId).toList());
        assertTrue(targets.targets().stream().allMatch(target -> target.flow() == null && target.step() != null));
    }

    @Test
    void prepareStepContexts_conditionNodesAreSkipped() {
        Process process = new Process();
        process.setId(1L);
        process.setRecipeId(9L);
        process.setType(ProcessType.MAIN);

        Process.ProcessNode step = stepNode("step-a", "cut", List.of(ingredient("onion", 2, "COUNT", "medium")), "Cut onions", "Chopped onions");
        Process.ProcessNode condition = new Process.ProcessNode();
        condition.setId("cond-1");
        condition.setKind(ProcessNodeKind.CONDITION);
        condition.setData(new LinkedHashMap<>());

        process.setNodes(new ArrayList<>(List.of(step, condition)));
        process.setEdges(new ArrayList<>());
        when(processRepository.findById(1L)).thenReturn(Optional.of(process));

        RecipeProcessVisualizationService.ProcessStepPreparation preparation = service.prepareStepContexts(1L);

        assertEquals(1, preparation.steps().size(), "CONDITION nodes must never be visualized");
        assertEquals("step-a", preparation.steps().get(0).node().getId());
    }

    @Test
    void prepareStepContexts_ordersByEdgesAndChainsPreviousExpectedOutput() {
        Process process = new Process();
        process.setId(2L);
        process.setRecipeId(9L);
        process.setType(ProcessType.MAIN);

        Process.ProcessNode stepA = stepNode("step-a", "cut", List.of(ingredient("onion", 2, "COUNT", "medium")), "Cut onions", "Chopped onions");
        Process.ProcessNode stepB = stepNode("step-b", "fry", List.of(), "Fry the onions", "Golden fried onions");

        // Nodes deliberately stored out of execution order; edges are authoritative.
        process.setNodes(new ArrayList<>(List.of(stepB, stepA)));
        process.setEdges(new ArrayList<>(List.of(edge("step-a", "step-b"))));
        when(processRepository.findById(2L)).thenReturn(Optional.of(process));

        RecipeProcessVisualizationService.ProcessStepPreparation preparation = service.prepareStepContexts(2L);

        assertEquals(List.of("step-a", "step-b"),
                preparation.steps().stream().map(ctx -> ctx.node().getId()).toList());
        assertNull(preparation.steps().get(0).input().previousExpectedOutput());
        assertEquals("Chopped onions", preparation.steps().get(1).input().previousExpectedOutput());
    }

    @Test
    void prepareStepContexts_multipleActionOnIngredients_areAllExtracted() {
        Process process = new Process();
        process.setId(3L);
        process.setRecipeId(9L);
        process.setType(ProcessType.MAIN);

        Process.ProcessNode step = stepNode("step-a", "cut", List.of(
                ingredient("onion", 2, "COUNT", "medium"),
                ingredient("tomato", 3, "COUNT", "large"),
                ingredient("chili", 1, "COUNT", "fine")
        ), "Cut everything", "Prepped vegetables");

        process.setNodes(new ArrayList<>(List.of(step)));
        process.setEdges(new ArrayList<>());
        when(processRepository.findById(3L)).thenReturn(Optional.of(process));

        RecipeProcessVisualizationService.ProcessStepPreparation preparation = service.prepareStepContexts(3L);
        RecipeProcessVisualizationInput input = preparation.steps().get(0).input();

        assertEquals(3, input.ingredients().size());
        assertTrue(input.ingredients().stream().anyMatch(i -> i.name().equals("Onion") && i.preparationStyle().equals("Medium")));
        assertTrue(input.ingredients().stream().anyMatch(i -> i.name().equals("Tomato") && i.preparationStyle().equals("Large")));
        assertTrue(input.ingredients().stream().anyMatch(i -> i.name().equals("Green Chili") && i.preparationStyle().equals("Fine")));
    }

    @Test
    void prepareStepContexts_subprocessReferences_resolveToNames() {
        Process subprocess = new Process();
        subprocess.setId(77L);
        subprocess.setName("Marinate Chicken");
        subprocess.setType(ProcessType.SUBPROCESS);
        subprocess.setRecipeId(9L);
        when(processRepository.findAllById(any())).thenReturn(List.of(subprocess));

        Process process = new Process();
        process.setId(4L);
        process.setRecipeId(9L);
        process.setType(ProcessType.MAIN);

        Map<String, Object> stepFields = new LinkedHashMap<>();
        stepFields.put("action", "cook");
        stepFields.put("actionDescription", "Cook the marinated chicken");
        stepFields.put("expectedOutput", "Cooked chicken");
        Map<String, Object> actionOn = new LinkedHashMap<>();
        actionOn.put("ingredients", List.of());
        actionOn.put("processes", List.of(Map.of("processId", 77)));
        stepFields.put("actionOn", actionOn);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("step", stepFields);

        Process.ProcessNode step = new Process.ProcessNode();
        step.setId("step-a");
        step.setKind(ProcessNodeKind.STEP);
        step.setData(data);

        process.setNodes(new ArrayList<>(List.of(step)));
        process.setEdges(new ArrayList<>());
        when(processRepository.findById(4L)).thenReturn(Optional.of(process));

        RecipeProcessVisualizationService.ProcessStepPreparation preparation = service.prepareStepContexts(4L);

        assertEquals(List.of("Marinate Chicken"), preparation.steps().get(0).input().subprocessNames());
    }

    @Test
    void attachResultsAndSave_writesEachStepAtomicallyWithoutResavingTheWholeProcess() {
        Process stale = new Process();
        stale.setId(5L);
        VisualizationAsset onion = new VisualizationAsset();
        onion.setId(70L);
        onion.setImagePrompt("chopped onion");
        onion.setImageUrl("https://cdn.example/onion.png");
        VisualizationAsset failed = new VisualizationAsset();
        failed.setId(71L);

        service.attachResultsAndSave(stale, Map.of("s1", onion, "s2", failed));

        verify(processRepository).setStepVisualization(5L, "s1", 70L, "chopped onion", "https://cdn.example/onion.png");
        verify(processRepository).setStepVisualization(5L, "s2", 71L, null, null);
        // Never a read-modify-save of the whole document: that would overwrite an editor save that
        // landed while the job ran, and re-create a process deleted meanwhile.
        verify(processRepository, never()).save(any());
        verify(processRepository, never()).findById(anyLong());
    }

    @Test
    void prepareStepContexts_processNotFound_throws() {
        when(processRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(RecipeProcessAiException.class, () -> service.prepareStepContexts(999L));
    }

    private Process.ProcessNode stepNode(
            String id, String actionId, List<Map<String, Object>> ingredients, String actionDescription, String expectedOutput) {
        Map<String, Object> stepFields = new LinkedHashMap<>();
        stepFields.put("action", actionId);
        stepFields.put("actionDescription", actionDescription);
        stepFields.put("expectedOutput", expectedOutput);
        Map<String, Object> actionOn = new LinkedHashMap<>();
        actionOn.put("ingredients", ingredients);
        actionOn.put("processes", List.of());
        stepFields.put("actionOn", actionOn);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("step", stepFields);

        Process.ProcessNode node = new Process.ProcessNode();
        node.setId(id);
        node.setKind(ProcessNodeKind.STEP);
        node.setData(data);
        return node;
    }

    private Map<String, Object> ingredient(String ingredientId, int quantity, String unit, String preparationStyleId) {
        Map<String, Object> ingredient = new LinkedHashMap<>();
        ingredient.put("ingredientId", ingredientId);
        ingredient.put("quantity", quantity);
        ingredient.put("unit", unit);
        ingredient.put("preparationStyleId", preparationStyleId);
        return ingredient;
    }

    private Process.ProcessEdge edge(String source, String target) {
        Process.ProcessEdge edge = new Process.ProcessEdge();
        edge.setId(source + "->" + target);
        edge.setSource(source);
        edge.setTarget(target);
        return edge;
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
