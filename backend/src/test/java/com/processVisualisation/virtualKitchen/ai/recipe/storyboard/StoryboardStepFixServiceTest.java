package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAsset;
import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAssetRepository;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifactService;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.AiArtifactSpec;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.codec.GeneratedImageCodec;
import com.processVisualisation.virtualKitchen.ai.platform.dispatch.AiClientResolver;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestOutcome;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestQueueService;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiWork;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import com.processVisualisation.virtualKitchen.ai.platform.response.AiJsonResponseSupport;
import com.processVisualisation.virtualKitchen.ai.platform.routing.FallbackReason;
import com.processVisualisation.virtualKitchen.ai.platform.routing.ModelSelectionOutcome;
import com.processVisualisation.virtualKitchen.ai.provider.AiClient;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient;
import com.processVisualisation.virtualKitchen.ai.provider.ImageStorageClient;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiResponse;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.dto.StoryboardStepFixResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.StoryboardStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.StoryboardStepFix;
import com.processVisualisation.virtualKitchen.common.concurrent.NamedTask;
import com.processVisualisation.virtualKitchen.common.concurrent.TaskPool;
import com.processVisualisation.virtualKitchen.common.persistence.SequenceGeneratorService;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessNodeKind;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import com.processVisualisation.virtualKitchen.recipe.repository.ProcessRepository;
import com.processVisualisation.virtualKitchen.recipe.repository.RecipeTemplateRepository;
import com.processVisualisation.virtualKitchen.ai.platform.response.AiResponseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoryboardStepFixServiceTest {

    private static final long RECIPE_ID = 62L;
    private static final long PROCESS_ID = 5L;
    private static final long OWNER = 7L;
    private static final byte[] OLD_IMAGE = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
    private static final byte[] NEW_IMAGE = {(byte) 0x89, 'P', 'N', 'G', 9, 9, 9};

    private final Map<String, StoryboardStepFix> fixes = new HashMap<>();
    private final Map<String, VisualizationAsset> assets = new HashMap<>();
    private AiRequestQueueService queueService;
    private AiClient textClient;
    private ImageGenerationClient imageClient;
    private ImageStorageClient storage;
    private ProcessRepository processRepository;
    private StoryboardStepFixService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        StoryboardStepFixRepository fixRepository = mock(StoryboardStepFixRepository.class);
        when(fixRepository.findByActiveKey(anyString())).thenReturn(Optional.empty());
        when(fixRepository.insert(any(StoryboardStepFix.class))).thenAnswer(invocation -> {
            StoryboardStepFix fix = invocation.getArgument(0);
            fixes.put(fix.getId(), fix);
            return fix;
        });
        when(fixRepository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(fixes.get(invocation.<String>getArgument(0))));

        RecipeTemplateRepository recipes = mock(RecipeTemplateRepository.class);
        RecipeTemplate recipe = new RecipeTemplate();
        recipe.setId(RECIPE_ID);
        recipe.setCreatedBy(OWNER);
        when(recipes.findById(RECIPE_ID)).thenReturn(Optional.of(recipe));

        processRepository = mock(ProcessRepository.class);
        Process process = new Process();
        process.setId(PROCESS_ID);
        process.setRecipeId(RECIPE_ID);
        Process.ProcessNode node = new Process.ProcessNode();
        node.setId("fry");
        node.setKind(ProcessNodeKind.STEP);
        process.setNodes(List.of(node));
        when(processRepository.findById(PROCESS_ID)).thenReturn(Optional.of(process));

        VisualizationAssetRepository assetRepository = mock(VisualizationAssetRepository.class);
        when(assetRepository.findByVisualizationKey(anyString())).thenAnswer(invocation -> Optional.ofNullable(assets.get(invocation.<String>getArgument(0))));
        when(assetRepository.save(any(VisualizationAsset.class))).thenAnswer(invocation -> {
            VisualizationAsset asset = invocation.getArgument(0);
            assets.put(asset.getVisualizationKey(), asset);
            return asset;
        });
        VisualizationAsset fry = new VisualizationAsset();
        fry.setId(11L);
        fry.setVisualizationKey("62::fry");
        fry.setImagePrompt("Onions frying in a steel pan");
        fry.setImageUrl("https://images.test/old-fry.png");
        fry.setSourceGridKey("storyboard:sb1");
        assets.put("62::fry", fry);

        RecipeStoryboardRepository storyboards = mock(RecipeStoryboardRepository.class);
        when(storyboards.findFirstByRecipeIdAndStatusOrderByCreatedAtDesc(RECIPE_ID, StoryboardStatus.COMPLETED)).thenReturn(Optional.empty());

        ModelDefinition model = new ModelDefinition();
        model.setKey("test-model");
        model.setProviderModelId("provider-model");
        ModelSelectionOutcome selection = new ModelSelectionOutcome(model, false, FallbackReason.NONE, null);
        AiClientResolver resolver = mock(AiClientResolver.class);
        textClient = mock(AiClient.class);
        imageClient = mock(ImageGenerationClient.class);
        when(resolver.resolveTextClient(model)).thenReturn(textClient);
        when(resolver.resolveImageClient(model)).thenReturn(imageClient);
        when(imageClient.supportsReferenceImages()).thenReturn(true);
        when(imageClient.generate(anyString(), any(), any())).thenReturn(new ImageGenerationClient.GeneratedImage("image/png", NEW_IMAGE));

        queueService = mock(AiRequestQueueService.class);
        when(queueService.executeBounded(eq(OWNER), eq(AiCapability.TEXT_TO_TEXT), isNull(), isNull(),
                eq(StoryboardStepFixService.PROMPT_CORRELATION_TYPE), anyString(), any(AiWork.class)))
                .thenAnswer(invocation -> AiRequestOutcome.fresh("req-text", ((AiWork<Object>) invocation.getArgument(6)).run(selection), selection, null));
        when(queueService.executeInline(eq(OWNER), eq(AiCapability.TEXT_TO_IMAGE), isNull(), isNull(),
                eq(StoryboardStepFixService.IMAGE_CORRELATION_TYPE), anyString(), any(AiArtifactSpec.class), any(AiWork.class)))
                .thenAnswer(invocation -> AiRequestOutcome.fresh("req-image", ((AiWork<Object>) invocation.getArgument(7)).run(selection), selection, null));

        storage = mock(ImageStorageClient.class);
        when(storage.download("https://images.test/old-fry.png")).thenReturn(new ImageStorageClient.StoredImage("image/png", OLD_IMAGE));
        when(storage.upload(any(), anyString(), anyString())).thenAnswer(invocation -> "https://images.test/" + invocation.getArgument(2));

        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        // Conditional job writes (claim, guarded status updates) read the matched count; a real database answers 1.
        org.mockito.Mockito.when(mongoTemplate.updateFirst(org.mockito.ArgumentMatchers.any(org.springframework.data.mongodb.core.query.Query.class),
                org.mockito.ArgumentMatchers.any(org.springframework.data.mongodb.core.query.UpdateDefinition.class),
                org.mockito.ArgumentMatchers.<Class<?>>any()))
                .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));
        TaskPool taskPool = mock(TaskPool.class);
        when(taskPool.submit(any(NamedTask.class))).thenAnswer(invocation -> {
            ((NamedTask<Object>) invocation.getArgument(0)).task().execute();
            return CompletableFuture.completedFuture(null);
        });
        SequenceGeneratorService sequences = mock(SequenceGeneratorService.class);

        service = new StoryboardStepFixService(fixRepository, storyboards, recipes, processRepository, assetRepository, sequences,
                queueService, resolver, new AiJsonResponseSupport(mock(AiResponseService.class)), mock(AiArtifactService.class),
                mock(GeneratedImageCodec.class), new StoryboardStepFixArtifactConsumer(storage, fixRepository, mongoTemplate),
                storage, new StoryboardProperties(), mongoTemplate, taskPool);
    }

    @Test
    void aFix_rewritesThePrompt_redrawsAgainstTheStepsImage_andMakesItTheStepsOwn() throws Exception {
        when(textClient.chat(any())).thenReturn(response("{\"imagePrompt\":\"Golden onions frying in a steel pan\"}"));

        StoryboardStepFixResponseDTO started = service.startFix(OWNER, RECIPE_ID, PROCESS_ID, "fry", "The onions should be golden, not raw");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ImageGenerationClient.ReferenceImage>> references = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(imageClient).generate(prompt.capture(), references.capture(), any());
        assertTrue(prompt.getValue().startsWith(StoryboardStepFixService.REFERENCE_PREFIX));
        assertTrue(prompt.getValue().endsWith("Golden onions frying in a steel pan"));
        assertEquals(1, references.getValue().size());

        VisualizationAsset fry = assets.get("62::fry");
        assertEquals("Golden onions frying in a steel pan", fry.getImagePrompt());
        assertEquals("https://images.test/storyboards/62/steps/fry/" + started.getFixId() + ".png", fry.getImageUrl());
        assertEquals(StoryboardStepFixService.SOURCE_PREFIX + started.getFixId(), fry.getSourceGridKey());
        verify(processRepository).setStepVisualization(PROCESS_ID, "fry", 11L, fry.getImagePrompt(), fry.getImageUrl());
        // The step's previous image is kept on the fix so it can be undone.
        assertEquals("https://images.test/old-fry.png", fixes.get(started.getFixId()).getPreviousImageUrl());
    }

    @Test
    void anUnusablePromptAnswer_fallsBackToTheOldPromptWithTheCorrection() throws Exception {
        when(textClient.chat(any())).thenThrow(new IllegalStateException("text model down"));

        service.startFix(OWNER, RECIPE_ID, PROCESS_ID, "fry", "Use a cast iron pan");

        assertEquals("Onions frying in a steel pan Correction requested by the cook: Use a cast iron pan",
                assets.get("62::fry").getImagePrompt());
    }

    @Test
    void anImageModelWithoutReferences_getsThePromptAlone() throws Exception {
        when(imageClient.supportsReferenceImages()).thenReturn(false);
        when(textClient.chat(any())).thenReturn(response("{\"imagePrompt\":\"New prompt\"}"));

        service.startFix(OWNER, RECIPE_ID, PROCESS_ID, "fry", "Brighter light");

        verify(imageClient).generate(eq("New prompt"), eq(List.of()), any());
    }

    @Test
    void aFixCanBeUndone_untilTheStepsImageChangesAgain() throws Exception {
        when(textClient.chat(any())).thenReturn(response("{\"imagePrompt\":\"New prompt\"}"));
        StoryboardStepFixResponseDTO started = service.startFix(OWNER, RECIPE_ID, PROCESS_ID, "fry", "Brighter light");
        fixes.get(started.getFixId()).setStatus(StoryboardStatus.COMPLETED);

        service.revert(OWNER, RECIPE_ID, started.getFixId());

        VisualizationAsset fry = assets.get("62::fry");
        assertEquals("https://images.test/old-fry.png", fry.getImageUrl());
        assertEquals("Onions frying in a steel pan", fry.getImagePrompt());
        assertEquals("storyboard:sb1", fry.getSourceGridKey());
        verify(processRepository).setStepVisualization(PROCESS_ID, "fry", 11L, "Onions frying in a steel pan", "https://images.test/old-fry.png");

        assertThrows(RecipeStoryboardException.class, () -> service.revert(OWNER, RECIPE_ID, started.getFixId()));
    }

    @Test
    void onlyTheOwner_withFeedback_onARealStep_canStartAFix() {
        assertThrows(RecipeAccessDeniedException.class, () -> service.startFix(99L, RECIPE_ID, PROCESS_ID, "fry", "x"));
        assertThrows(RecipeStoryboardException.class, () -> service.startFix(OWNER, RECIPE_ID, PROCESS_ID, "fry", "  "));
        assertThrows(java.util.NoSuchElementException.class, () -> service.startFix(OWNER, RECIPE_ID, PROCESS_ID, "nope", "x"));
        verify(queueService, never()).executeInline(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static AiResponse response(String content) {
        AiResponse response = new AiResponse();
        response.setContent(content);
        return response;
    }
}
