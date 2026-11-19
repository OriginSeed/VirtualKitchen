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
import com.processVisualisation.virtualKitchen.ai.platform.routing.FallbackReason;
import com.processVisualisation.virtualKitchen.ai.platform.routing.ModelSelectionOutcome;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient;
import com.processVisualisation.virtualKitchen.ai.provider.ImageStorageClient;
import com.processVisualisation.virtualKitchen.ai.recipe.RecipeProcessAiException;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.dto.RecipeStoryboardResponseDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.RecipeStoryboard;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.StoryboardStage;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.StoryboardStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.StoryboardGridSlicingTest;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationImageProfiles;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationImageProperties;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationJobRepository;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.model.VisualizationJob;
import com.processVisualisation.virtualKitchen.common.concurrent.NamedTask;
import com.processVisualisation.virtualKitchen.common.concurrent.TaskPool;
import com.processVisualisation.virtualKitchen.common.persistence.SequenceGeneratorService;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import com.processVisualisation.virtualKitchen.recipe.model.Visibility;
import com.processVisualisation.virtualKitchen.recipe.repository.ProcessRepository;
import com.processVisualisation.virtualKitchen.recipe.repository.RecipeTemplateRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecipeStoryboardJobServiceTest {

    private static final long RECIPE_ID = 62L;
    private static final long OWNER = 7L;
    private static final String MASTER_PROMPT = "One image, 3 rows and 3 columns. Panel 1 to Panel 7. No text.";

    private RecipeStoryboardRepository storyboardRepository;
    private ProcessRepository processRepository;
    private VisualizationJobRepository visualizationJobRepository;
    private StoryboardPlanner planner;
    private StoryboardMasterPromptWriter masterPromptWriter;
    private ImageGenerationClient imageClient;
    private ImageStorageClient imageStorageClient;
    private MongoTemplate mongoTemplate;
    private TaskPool taskPool;
    private StoryboardProperties properties;
    private RecipeStoryboardJobService service;

    private RecipeTemplate recipe;
    private List<Process> processes;
    private StoryboardSource source;
    private final Map<String, VisualizationAsset> assets = new ConcurrentHashMap<>();
    private final Map<String, RecipeStoryboard> stored = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        storyboardRepository = mock(RecipeStoryboardRepository.class);
        RecipeTemplateRepository recipeTemplateRepository = mock(RecipeTemplateRepository.class);
        processRepository = mock(ProcessRepository.class);
        RecipeProcessVisualizationService visualizationService = mock(RecipeProcessVisualizationService.class);
        visualizationJobRepository = mock(VisualizationJobRepository.class);
        planner = mock(StoryboardPlanner.class);
        masterPromptWriter = mock(StoryboardMasterPromptWriter.class);
        imageClient = mock(ImageGenerationClient.class);
        imageStorageClient = mock(ImageStorageClient.class);
        mongoTemplate = mock(MongoTemplate.class);
        taskPool = mock(TaskPool.class);
        AiRequestQueueService queueService = mock(AiRequestQueueService.class);
        AiClientResolver clientResolver = mock(AiClientResolver.class);
        VisualizationAssetRepository assetRepository = mock(VisualizationAssetRepository.class);
        SequenceGeneratorService sequences = mock(SequenceGeneratorService.class);
        VisualizationImageProfiles imageProfiles = mock(VisualizationImageProfiles.class);
        properties = new StoryboardProperties();

        recipe = new RecipeTemplate();
        recipe.setId(RECIPE_ID);
        recipe.setName("Test dish");
        recipe.setCreatedBy(OWNER);
        recipe.setMainProcessId(StoryboardTestFixtures.MAIN_ID);
        recipe.setProcessRevision(4L);
        when(recipeTemplateRepository.findById(RECIPE_ID)).thenReturn(Optional.of(recipe));

        processes = new ArrayList<>(StoryboardTestFixtures.layeredRecipe());
        source = StoryboardTestFixtures.source(processes);
        when(processRepository.findByRecipeId(RECIPE_ID)).thenAnswer(invocation -> processes);
        when(visualizationService.prepareStepContexts(anyLong())).thenAnswer(invocation -> StoryboardTestFixtures.prepared(
                processes.stream().filter(process -> process.getId().equals(invocation.getArgument(0))).toList()).get(0));
        when(visualizationJobRepository.findByRecipeIdAndStatusIn(anyString(), any())).thenReturn(List.of());

        when(storyboardRepository.findByActiveKey(anyString())).thenReturn(Optional.empty());
        when(storyboardRepository.findFirstByRecipeIdOrderByCreatedAtDesc(RECIPE_ID)).thenReturn(Optional.empty());
        when(storyboardRepository.insert(any(RecipeStoryboard.class))).thenAnswer(invocation -> {
            RecipeStoryboard storyboard = invocation.getArgument(0);
            stored.put(storyboard.getId(), storyboard);
            return storyboard;
        });
        when(storyboardRepository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.<String>getArgument(0))));

        StoryboardSpecValidator.Result plan = StoryboardSpecValidator.validate(StoryboardTestFixtures.layeredPlan(source), source, 16);
        when(planner.plan(eq(OWNER), anyString(), any(), anyInt())).thenAnswer(invocation -> new StoryboardPlanner.Planned(
                "Biryani", "A layered rice dish", plan.panels(), plan.omitted(), new ArrayList<>(), "test-text"));
        when(masterPromptWriter.write(eq(OWNER), any(), any(), any()))
                .thenReturn(new StoryboardMasterPromptWriter.Written(MASTER_PROMPT, false));
        when(imageProfiles.scene()).thenReturn(new VisualizationImageProperties.Scene());

        ModelDefinition model = new ModelDefinition();
        model.setKey("test-image");
        model.setProviderModelId("provider-image");
        ModelSelectionOutcome selection = new ModelSelectionOutcome(model, false, FallbackReason.NONE, null);
        when(clientResolver.resolveImageClient(model)).thenReturn(imageClient);
        // Seven panels are a 3x3 grid.
        byte[] image = StoryboardGridSlicingTest.grid(300, 300, 3, 3, 4, 0);
        when(imageClient.generate(anyString(), any(), any()))
                .thenReturn(new ImageGenerationClient.GeneratedImage("image/png", image));
        when(queueService.executeInline(eq(OWNER), eq(AiCapability.TEXT_TO_IMAGE), isNull(), isNull(),
                eq("recipe-storyboard-image"), anyString(), any(AiArtifactSpec.class), any(AiWork.class)))
                .thenAnswer(invocation -> AiRequestOutcome.fresh("request-1",
                        ((AiWork<Object>) invocation.getArgument(7)).run(selection), selection, null));
        when(imageStorageClient.upload(any(), anyString(), anyString()))
                .thenAnswer(invocation -> "https://images.test/" + invocation.getArgument(2));

        AtomicLong ids = new AtomicLong(100);
        when(sequences.generateSequence(anyString())).thenAnswer(invocation -> ids.incrementAndGet());
        when(assetRepository.findByVisualizationKey(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(assets.get(invocation.<String>getArgument(0))));
        when(assetRepository.save(any(VisualizationAsset.class))).thenAnswer(invocation -> {
            VisualizationAsset asset = invocation.getArgument(0);
            assets.put(asset.getVisualizationKey(), asset);
            return asset;
        });
        // The pool runs the job at once, so a start returns the finished storyboard.
        when(taskPool.submit(any(NamedTask.class))).thenAnswer(invocation -> {
            ((NamedTask<Object>) invocation.getArgument(0)).task().execute();
            return CompletableFuture.completedFuture(null);
        });

        service = new RecipeStoryboardJobService(storyboardRepository, recipeTemplateRepository, processRepository,
                visualizationService, visualizationJobRepository, planner, masterPromptWriter,
                new StoryboardPanelAttacher(imageStorageClient, assetRepository, sequences, processRepository),
                new StoryboardImageArtifactConsumer(imageStorageClient, storyboardRepository, mongoTemplate),
                queueService, clientResolver, mock(AiArtifactService.class), mock(GeneratedImageCodec.class), imageProfiles,
                properties, mongoTemplate, taskPool);
    }

    @Test
    void aStoryboard_isPlanned_drawnWithExactlyOneImageRequest_cut_andEachPanelPutOnItsLastStep() throws Exception {
        RecipeStoryboardResponseDTO storyboard = service.startJob(OWNER, RECIPE_ID, false, false);

        // One image request: the master prompt, no reference images, the configured size and the chosen model.
        ArgumentCaptor<ImageGenerationClient.ImageGenerationSettings> settings =
                ArgumentCaptor.forClass(ImageGenerationClient.ImageGenerationSettings.class);
        verify(imageClient, times(1)).generate(eq(MASTER_PROMPT), eq(List.of()), settings.capture());
        assertEquals(1024, settings.getValue().width());
        assertEquals(1024, settings.getValue().height());
        assertEquals("provider-image", settings.getValue().modelId());

        assertEquals(StoryboardStatus.COMPLETED, lastSet("status"));
        assertEquals(List.of(StoryboardStage.PLAN, StoryboardStage.PROMPT, StoryboardStage.IMAGE, StoryboardStage.CROP), allSet("stage"));
        assertTrue(unsetsActiveKey());

        assertEquals("Biryani", storyboard.getTitle());
        assertEquals(MASTER_PROMPT, storyboard.getMasterPrompt());
        assertEquals(3, storyboard.getRows());
        assertEquals(3, storyboard.getColumns());
        assertEquals("test-text", storyboard.getTextModelKey());
        assertEquals("test-image", storyboard.getImageModelKey());
        assertTrue(storyboard.getImageUrl().startsWith("https://images.test/storyboards/62/" + storyboard.getJobId() + "/"));
        assertTrue(storyboard.getWarnings().isEmpty());

        // Every panel has its own image, cut from the combined one.
        assertEquals(7, storyboard.getPanels().size());
        for (RecipeStoryboardResponseDTO.PanelDTO panel : storyboard.getPanels()) {
            assertEquals("https://images.test/storyboards/62/" + storyboard.getJobId() + "/panels/0" + panel.getSequence() + ".png",
                    panel.getImageUrl());
        }

        // Each panel's image goes on ONE step, the last it covers, so no picture repeats down the recipe.
        assertEquals(List.of("mix", "rest", "boil", "drain", "fry", "cook", "dum"),
                storyboard.getSteps().stream().map(RecipeStoryboardResponseDTO.StepDTO::getStepId).toList());
        assertTrue(storyboard.getSteps().stream().allMatch(RecipeStoryboardResponseDTO.StepDTO::isAttached));
        assertTrue(storyboard.getSteps().stream().noneMatch(RecipeStoryboardResponseDTO.StepDTO::isCleared));
        RecipeStoryboardResponseDTO.StepDTO rest = step(storyboard, "rest");
        assertEquals("P2", rest.getPanelId());
        assertEquals(storyboard.getPanels().get(1).getImageUrl(), rest.getImageUrl());
        // The panel's earlier steps, and the check, get nothing.
        assertNull(assets.get("62::coat"));
        assertNull(assets.get("62::heat"));

        // Written the way step visuals are: an asset for the step, then the node itself.
        VisualizationAsset asset = assets.get("62::rest");
        assertEquals(rest.getImageUrl(), asset.getImageUrl());
        assertEquals("Photo of panel P2", asset.getImagePrompt());
        assertEquals("storyboard:" + storyboard.getJobId(), asset.getSourceGridKey());
        assertEquals("test-image", asset.getResolvedModelKey());
        assertNull(asset.getFocus());
        verify(processRepository).setStepVisualization(StoryboardTestFixtures.PREP_ID, "rest", asset.getId(),
                "Photo of panel P2", rest.getImageUrl());
        // The main panel that covers heat, add, cook and the check puts its image on "cook".
        verify(processRepository).setStepVisualization(eq(StoryboardTestFixtures.MAIN_ID), eq("cook"), anyLong(),
                eq("Photo of panel P6"), eq(storyboard.getPanels().get(5).getImageUrl()));
        verify(processRepository).setStepVisualization(eq(StoryboardTestFixtures.MAIN_ID), eq("dum"), anyLong(),
                eq("Photo of panel P7"), eq(storyboard.getPanels().get(6).getImageUrl()));
        verify(processRepository, times(7)).setStepVisualization(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    void aStepsOwnImage_isKept_unlessAskedToReplace_andIsNeverRemovedFromAnEarlierStepOfAPanel() throws Exception {
        // "heat" is an earlier step of panel P6 and has its own image; "cook" is P6's last step and has one too.
        processes.get(0).getNodes().get(0).getData().put("imageUrl", "https://images.test/old-heat.png");
        VisualizationAsset cook = new VisualizationAsset();
        cook.setId(5L);
        cook.setVisualizationKey("62::cook");
        cook.setImageUrl("https://images.test/old-cook.png");
        assets.put(cook.getVisualizationKey(), cook);

        RecipeStoryboardResponseDTO kept = service.startJob(OWNER, RECIPE_ID, false, false);

        assertFalse(step(kept, "cook").isAttached());
        assertNull(step(kept, "cook").getImageUrl());
        assertTrue(step(kept, "dum").isAttached());
        assertEquals("https://images.test/old-cook.png", assets.get("62::cook").getImageUrl());
        assertTrue(kept.getSteps().stream().noneMatch(step -> step.getStepId().equals("heat")));
        verify(processRepository, never()).setStepVisualization(anyLong(), eq("heat"), any(), any(), any());
        verify(processRepository, never()).setStepVisualization(anyLong(), eq("cook"), any(), any(), any());

        RecipeStoryboardResponseDTO replaced = service.startJob(OWNER, RECIPE_ID, true, true);

        assertTrue(step(replaced, "cook").isAttached());
        assertEquals(step(replaced, "cook").getImageUrl(), assets.get("62::cook").getImageUrl());
        assertEquals(5L, assets.get("62::cook").getId());
        // Even when replacing, an earlier step of the panel keeps the image that is its own.
        assertTrue(replaced.getSteps().stream().noneMatch(step -> step.getStepId().equals("heat")));
        verify(processRepository, never()).setStepVisualization(anyLong(), eq("heat"), any(), any(), any());
    }

    @Test
    void imagesAnEarlierStoryboardPutOnTheSteps_areReplacedOnTheLastStep_andRemovedFromTheOthers() throws Exception {
        // As an earlier storyboard left them: the same panel image on both steps of P2.
        for (String stepId : List.of("coat", "rest")) {
            VisualizationAsset earlier = new VisualizationAsset();
            earlier.setId(stepId.equals("coat") ? 11L : 12L);
            earlier.setVisualizationKey("62::" + stepId);
            earlier.setImageUrl("https://images.test/earlier-panel-2.png");
            earlier.setImagePrompt("Earlier panel prompt");
            earlier.setSourceGridKey("storyboard:sb-earlier");
            assets.put(earlier.getVisualizationKey(), earlier);
        }
        processes.get(1).getNodes().forEach(node -> {
            if (List.of("coat", "rest").contains(node.getId())) node.getData().put("imageUrl", "https://images.test/earlier-panel-2.png");
        });

        // Not asked to replace: these are not the user's own images.
        RecipeStoryboardResponseDTO storyboard = service.startJob(OWNER, RECIPE_ID, false, false);

        RecipeStoryboardResponseDTO.StepDTO coat = step(storyboard, "coat");
        assertTrue(coat.isCleared());
        assertFalse(coat.isAttached());
        assertNull(coat.getImageUrl());
        assertNull(assets.get("62::coat").getImageUrl());
        assertNull(assets.get("62::coat").getSourceGridKey());
        verify(processRepository).setStepVisualization(StoryboardTestFixtures.PREP_ID, "coat", null, null, null);

        RecipeStoryboardResponseDTO.StepDTO rest = step(storyboard, "rest");
        assertTrue(rest.isAttached());
        assertFalse(rest.isCleared());
        assertEquals(storyboard.getPanels().get(1).getImageUrl(), assets.get("62::rest").getImageUrl());
        assertEquals("storyboard:" + storyboard.getJobId(), assets.get("62::rest").getSourceGridKey());
        assertEquals(12L, assets.get("62::rest").getId());
        assertEquals(8, storyboard.getSteps().size());
    }

    @Test
    void aFailedImageRequest_failsTheStoryboardAtTheImageStage_andChangesNoStep() throws Exception {
        when(imageClient.generate(anyString(), any(), any())).thenThrow(new IllegalStateException("image provider down"));

        RecipeStoryboardResponseDTO storyboard = service.startJob(OWNER, RECIPE_ID, false, false);

        assertEquals(StoryboardStatus.FAILED, lastSet("status"));
        assertEquals(StoryboardStage.IMAGE, lastSet("failedStage"));
        assertEquals("image provider down", lastSet("errorMessage"));
        assertTrue(unsetsActiveKey());
        assertNull(storyboard.getImageUrl());
        assertTrue(storyboard.getSteps().isEmpty());
        verify(processRepository, never()).setStepVisualization(anyLong(), anyString(), any(), any(), any());
        // The plan and the prompt were stored before the image was asked for.
        assertNotNull(lastSet("panels"));
        assertEquals(MASTER_PROMPT, lastSet("masterPrompt"));
    }

    @Test
    void aRetryOnTheSameRecipe_continuesFromTheFailedStage_withoutPayingForTheTextCallsAgain() throws Exception {
        when(storyboardRepository.findFirstByRecipeIdOrderByCreatedAtDesc(RECIPE_ID)).thenReturn(Optional.of(failedAtImage(source.hash())));

        RecipeStoryboardResponseDTO storyboard = service.startJob(OWNER, RECIPE_ID, false, false);

        verify(planner, never()).plan(any(), any(), any(), anyInt());
        verify(masterPromptWriter, never()).write(any(), any(), any(), any());
        verify(imageClient, times(1)).generate(eq("The earlier master prompt"), eq(List.of()), any());
        assertEquals(StoryboardStatus.COMPLETED, lastSet("status"));
        assertEquals(List.of(StoryboardStage.IMAGE, StoryboardStage.CROP), allSet("stage"));
        assertEquals(7, storyboard.getPanels().size());
        assertEquals("earlier-text", storyboard.getTextModelKey());
        assertEquals(7, storyboard.getSteps().size());
    }

    @Test
    void aChangedRecipe_orAnExplicitRegenerate_plansAgain() throws Exception {
        when(storyboardRepository.findFirstByRecipeIdOrderByCreatedAtDesc(RECIPE_ID)).thenReturn(Optional.of(failedAtImage("another-hash")));
        service.startJob(OWNER, RECIPE_ID, false, false);
        verify(planner, times(1)).plan(any(), any(), any(), anyInt());

        when(storyboardRepository.findFirstByRecipeIdOrderByCreatedAtDesc(RECIPE_ID)).thenReturn(Optional.of(failedAtImage(source.hash())));
        service.startJob(OWNER, RECIPE_ID, true, false);
        verify(planner, times(2)).plan(any(), any(), any(), anyInt());
        verify(masterPromptWriter, times(2)).write(any(), any(), any(), any());
    }

    @Test
    void aPlanThatCannotBeMade_failsTheStoryboardAtThePlanStage_beforeAnyImageIsRequested() throws Exception {
        when(planner.plan(any(), any(), any(), anyInt()))
                .thenThrow(new RecipeProcessAiException("Unable to plan a storyboard for this recipe: no panels"));

        service.startJob(OWNER, RECIPE_ID, false, false);

        assertEquals(StoryboardStatus.FAILED, lastSet("status"));
        assertEquals(StoryboardStage.PLAN, lastSet("failedStage"));
        verify(imageClient, never()).generate(anyString(), any(), any());
    }

    @Test
    void anImageThatCannotBeCut_stillGivesAStoryboard_withAWarning_andNoStepIsChanged() throws Exception {
        when(imageClient.generate(anyString(), any(), any()))
                .thenReturn(new ImageGenerationClient.GeneratedImage("image/png", new byte[]{1, 2, 3}));

        RecipeStoryboardResponseDTO storyboard = service.startJob(OWNER, RECIPE_ID, false, false);

        assertEquals(StoryboardStatus.COMPLETED, lastSet("status"));
        assertNotNull(storyboard.getImageUrl());
        assertEquals(1, storyboard.getWarnings().size());
        assertTrue(storyboard.getWarnings().get(0).contains("could not be cut"), storyboard.getWarnings().toString());
        assertTrue(storyboard.getPanels().stream().allMatch(panel -> panel.getImageUrl() == null));
        verify(processRepository, never()).setStepVisualization(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    void aTemplateMasterPrompt_isReportedAsAWarning() throws Exception {
        when(masterPromptWriter.write(any(), any(), any(), any()))
                .thenReturn(new StoryboardMasterPromptWriter.Written("Panel 1 ... Panel 7", true));

        RecipeStoryboardResponseDTO storyboard = service.startJob(OWNER, RECIPE_ID, false, false);

        assertEquals(StoryboardStatus.COMPLETED, lastSet("status"));
        assertEquals(List.of("The image prompt was assembled from the panels by a fixed template."), storyboard.getWarnings());
    }

    @Test
    void aSecondStart_joinsTheRunningStoryboard() throws Exception {
        RecipeStoryboard running = new RecipeStoryboard();
        running.setId("sb-running");
        running.setRecipeId(RECIPE_ID);
        running.setUserId(OWNER);
        running.setStatus(StoryboardStatus.IN_PROGRESS);
        running.setStage(StoryboardStage.IMAGE);
        when(storyboardRepository.findByActiveKey("storyboard:62")).thenReturn(Optional.of(running));

        RecipeStoryboardResponseDTO joined = service.startJob(OWNER, RECIPE_ID, false, false);

        assertTrue(joined.isReused());
        assertEquals("sb-running", joined.getJobId());
        assertEquals("IMAGE", joined.getStage());
        verify(taskPool, never()).submit(any());
        verify(storyboardRepository, never()).insert(any(RecipeStoryboard.class));
        assertEquals("sb-running", service.findActive(OWNER, RECIPE_ID).orElseThrow().getJobId());
        assertTrue(service.findActive(8L, RECIPE_ID).isEmpty());
    }

    @Test
    void onlyTheOwnerStartsAStoryboard_andOnlyForARecipeThatCanBeToldInOneImage() throws Exception {
        assertThrows(RecipeAccessDeniedException.class, () -> service.startJob(8L, RECIPE_ID, false, false));
        assertThrows(NoSuchElementException.class, () -> service.startJob(OWNER, 999L, false, false));

        properties.setMaxSourceSteps(5);
        RecipeStoryboardException tooLong = assertThrows(RecipeStoryboardException.class,
                () -> service.startJob(OWNER, RECIPE_ID, false, false));
        assertTrue(tooLong.getMessage().contains("13 steps and checks"), tooLong.getMessage());
        properties.setMaxSourceSteps(80);

        when(visualizationJobRepository.findByRecipeIdAndStatusIn(eq("62"), any())).thenReturn(List.of(new VisualizationJob()));
        RecipeStoryboardException busy = assertThrows(RecipeStoryboardException.class,
                () -> service.startJob(OWNER, RECIPE_ID, false, false));
        assertEquals(409, busy.getStatus().value());
        when(visualizationJobRepository.findByRecipeIdAndStatusIn(eq("62"), any())).thenReturn(List.of());

        processes.clear();
        RecipeStoryboardException empty = assertThrows(RecipeStoryboardException.class,
                () -> service.startJob(OWNER, RECIPE_ID, false, false));
        assertEquals(422, empty.getStatus().value());
        verify(taskPool, never()).submit(any());
    }

    @Test
    void theRecipesStoryboard_isItsLatestCompletedOne_readableByTheOwner_andByAnyoneOnceTheRecipeIsPublic() throws Exception {
        RecipeStoryboard completed = failedAtImage(source.hash());
        completed.setStatus(StoryboardStatus.COMPLETED);
        completed.setImageUrl("https://images.test/storyboard.png");
        when(storyboardRepository.findFirstByRecipeIdAndStatusOrderByCreatedAtDesc(RECIPE_ID, StoryboardStatus.COMPLETED))
                .thenReturn(Optional.of(completed));

        RecipeStoryboardResponseDTO own = service.findLatest(OWNER, RECIPE_ID).orElseThrow();
        assertEquals("https://images.test/storyboard.png", own.getImageUrl());
        assertEquals("The earlier master prompt", own.getMasterPrompt());
        assertFalse(own.isOutdated());

        assertThrows(RecipeAccessDeniedException.class, () -> service.findLatest(8L, RECIPE_ID));
        recipe.setVisibility(Visibility.PUBLIC);
        RecipeStoryboardResponseDTO shared = service.findLatest(8L, RECIPE_ID).orElseThrow();
        assertEquals(7, shared.getPanels().size());
        assertNull(shared.getMasterPrompt());
        assertNull(shared.getPanels().get(0).getImagePrompt());

        // The recipe is edited afterwards: the storyboard is still shown, marked as outdated.
        StoryboardTestFixtures.add(processes.get(0), StoryboardTestFixtures.step("serve", "serve", "Served"));
        assertTrue(service.findLatest(OWNER, RECIPE_ID).orElseThrow().isOutdated());
    }

    @Test
    void aStoryboardOfAnotherUserOrRecipe_isReportedAsMissing() throws Exception {
        RecipeStoryboardResponseDTO storyboard = service.startJob(OWNER, RECIPE_ID, false, false);

        assertEquals(storyboard.getJobId(), service.getJobStatus(OWNER, RECIPE_ID, storyboard.getJobId()).getJobId());
        assertThrows(NoSuchElementException.class, () -> service.getJobStatus(8L, RECIPE_ID, storyboard.getJobId()));
        assertThrows(NoSuchElementException.class, () -> service.getJobStatus(OWNER, 63L, storyboard.getJobId()));
        assertThrows(NoSuchElementException.class, () -> service.getJobStatus(OWNER, RECIPE_ID, "unknown"));
    }

    // ------------------------------------------------------------------------------------------------- helpers

    @Test
    void fill_onAnUnchangedRecipe_reusesTheLatestStoryboard_withoutAnyAiCall_andPutsItsPanelsBackOnTheSteps() throws Exception {
        RecipeStoryboard completed = completedStoryboard();
        org.mockito.Mockito.clearInvocations(planner, masterPromptWriter, imageClient, processRepository, mongoTemplate);

        RecipeStoryboardResponseDTO filled = service.startJob(OWNER, RECIPE_ID, StoryboardMode.FILL);

        verify(planner, never()).plan(any(), any(), any(), anyInt());
        verify(masterPromptWriter, never()).write(any(), any(), any(), any());
        verify(imageClient, never()).generate(anyString(), any(), any());
        verify(imageStorageClient, never()).download(anyString());
        assertEquals(List.of(StoryboardStage.CROP), allSet("stage"));
        assertEquals(StoryboardStatus.COMPLETED, lastSet("status"));
        assertEquals(completed.getImageUrl(), filled.getImageUrl());
        assertEquals(7, filled.getSteps().size());
        verify(processRepository, times(7)).setStepVisualization(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    void fill_cutsAReusedStoryboardAgain_whenItsPanelsHaveNoImage() throws Exception {
        RecipeStoryboard completed = completedStoryboard();
        completed.getPanels().forEach(panel -> panel.setImageUrl(null));
        when(imageStorageClient.download(completed.getImageUrl())).thenReturn(
                new ImageStorageClient.StoredImage("image/png", StoryboardGridSlicingTest.grid(300, 300, 3, 3, 4, 0)));
        service.setImageStorageClient(imageStorageClient);
        org.mockito.Mockito.clearInvocations(imageClient);

        RecipeStoryboardResponseDTO filled = service.startJob(OWNER, RECIPE_ID, StoryboardMode.FILL);

        verify(imageClient, never()).generate(anyString(), any(), any());
        verify(imageStorageClient).download(completed.getImageUrl());
        assertTrue(filled.getPanels().stream().allMatch(panel -> panel.getImageUrl() != null));
        assertEquals(7, filled.getSteps().size());
    }

    @Test
    void fill_onAChangedRecipe_drawsANewStoryboard_keepingTheStepsOwnImages_andReplaceRedoesEverything() throws Exception {
        RecipeStoryboard completed = completedStoryboard();
        completed.setSourceHash("another-hash");
        org.mockito.Mockito.clearInvocations(planner, imageClient);

        service.startJob(OWNER, RECIPE_ID, StoryboardMode.FILL);
        verify(planner, times(1)).plan(any(), any(), any(), anyInt());
        verify(imageClient, times(1)).generate(anyString(), any(), any());
        assertFalse(stored.values().stream().filter(sb -> sb != completed).findFirst().orElseThrow().isOverwriteExisting());

        completed.setSourceHash(source.hash());
        service.startJob(OWNER, RECIPE_ID, StoryboardMode.REPLACE);
        verify(planner, times(2)).plan(any(), any(), any(), anyInt());
        verify(imageClient, times(2)).generate(anyString(), any(), any());
    }

    /** Runs a first storyboard to completion and makes it the recipe's latest completed one. */
    private RecipeStoryboard completedStoryboard() {
        RecipeStoryboardResponseDTO first = service.startJob(OWNER, RECIPE_ID, false, false);
        RecipeStoryboard completed = stored.get(first.getJobId());
        completed.setStatus(StoryboardStatus.COMPLETED);
        when(storyboardRepository.findFirstByRecipeIdAndStatusOrderByCreatedAtDesc(RECIPE_ID, StoryboardStatus.COMPLETED))
                .thenReturn(Optional.of(completed));
        return completed;
    }

    /** An earlier attempt that planned its panels and wrote its prompt, then failed to get its image. */
    private RecipeStoryboard failedAtImage(String sourceHash) {
        StoryboardSpecValidator.Result plan = StoryboardSpecValidator.validate(StoryboardTestFixtures.layeredPlan(source), source, 16);
        RecipeStoryboard failed = new RecipeStoryboard();
        failed.setId("sb-earlier");
        failed.setRecipeId(RECIPE_ID);
        failed.setUserId(OWNER);
        failed.setStatus(StoryboardStatus.FAILED);
        failed.setFailedStage(StoryboardStage.IMAGE);
        failed.setSourceHash(sourceHash);
        failed.setTitle("Biryani");
        failed.setPanels(plan.panels());
        RecipeStoryboard.Layout layout = new RecipeStoryboard.Layout();
        layout.setRows(3);
        layout.setColumns(3);
        failed.setLayout(layout);
        failed.setMasterPrompt("The earlier master prompt");
        failed.setTextModelKey("earlier-text");
        return failed;
    }

    private static RecipeStoryboardResponseDTO.StepDTO step(RecipeStoryboardResponseDTO storyboard, String stepId) {
        return storyboard.getSteps().stream().filter(step -> step.getStepId().equals(stepId)).findFirst().orElseThrow();
    }

    private List<Update> updates() {
        ArgumentCaptor<Update> captor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, org.mockito.Mockito.atLeastOnce()).updateFirst(any(Query.class), captor.capture(), eq(RecipeStoryboard.class));
        return captor.getAllValues();
    }

    /** Every value {@code field} was set to, in order. */
    private List<Object> allSet(String field) {
        List<Object> values = new ArrayList<>();
        for (Update update : updates()) {
            Document set = update.getUpdateObject().get("$set", Document.class);
            if (set != null && set.containsKey(field)) values.add(set.get(field));
        }
        return values;
    }

    private Object lastSet(String field) {
        List<Object> values = allSet(field);
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }

    private boolean unsetsActiveKey() {
        return updates().stream().anyMatch(update -> {
            Document unset = update.getUpdateObject().get("$unset", Document.class);
            return unset != null && unset.containsKey("activeKey");
        });
    }
}
