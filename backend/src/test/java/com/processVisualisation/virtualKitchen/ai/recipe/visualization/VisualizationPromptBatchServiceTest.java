package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import com.processVisualisation.virtualKitchen.ai.platform.dispatch.AiClientResolver;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestOutcome;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestQueueService;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiWork;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelTier;
import com.processVisualisation.virtualKitchen.ai.platform.response.AiJsonResponseSupport;
import com.processVisualisation.virtualKitchen.ai.platform.response.AiResponseService;
import com.processVisualisation.virtualKitchen.ai.platform.routing.FallbackReason;
import com.processVisualisation.virtualKitchen.ai.platform.routing.ModelSelectionOutcome;
import com.processVisualisation.virtualKitchen.ai.provider.AiClient;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiRequest;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiResponse;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService.FlowContext;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService.VisualPreparation;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService.VisualTarget;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VisualizationPromptBatchServiceTest {

    private static final long USER = 1L;
    private static final long RECIPE = 100L;
    private static final long MAIN = 10L;
    private static final long PREP = 11L;

    private RecipeProcessVisualizationService visualizationService;
    private AiRequestQueueService queueService;
    private AiClient aiClient;
    private VisualizationPromptBatchService service;
    /** Stored prompts by visualization key, as the asset collection would hold them. */
    private final Map<String, String> storedPrompts = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        visualizationService = mock(RecipeProcessVisualizationService.class);
        queueService = mock(AiRequestQueueService.class);
        aiClient = mock(AiClient.class);

        when(visualizationService.existingPrompt(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(storedPrompts.get(inv.<String>getArgument(0))));
        when(visualizationService.savePrompt(anyString(), anyString())).thenAnswer(inv -> {
            storedPrompts.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        });

        ModelDefinition model = new ModelDefinition();
        model.setKey("test-text");
        model.setCapability(AiCapability.TEXT_TO_TEXT);
        model.setTier(ModelTier.OPEN_SOURCE);
        model.setProviderBean("testTextClient");
        model.setProviderModelId("test-text-model");
        model.setEnabled(true);
        ModelSelectionOutcome selection = new ModelSelectionOutcome(model, false, FallbackReason.NONE, null);
        // The queue just runs the work with the selected model, as it would on its ai-text pool.
        when(queueService.executeBounded(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            AiWork<Object> work = inv.getArgument(6);
            return AiRequestOutcome.fresh("job", work.run(selection), selection, null);
        });

        // Scene anchors off here, so these tests see the prompt batch alone; the scene tests below turn them on.
        service = batchService(false);
    }

    private VisualizationPromptBatchService batchService(boolean sceneAnchorEnabled) {
        VisualizationSceneAnchorService sceneAnchorService = mock(VisualizationSceneAnchorService.class);
        when(sceneAnchorService.isEnabled()).thenReturn(sceneAnchorEnabled);
        return new VisualizationPromptBatchService(
                visualizationService,
                new RecipeProcessVisualizationPromptBuilder(),
                queueService,
                new AiClientResolver(Map.of("testTextClient", aiClient), Map.of()),
                new AiJsonResponseSupport(mock(AiResponseService.class)),
                sceneAnchorService);
    }

    @Test
    void recipeWithoutAScene_asksForItInTheSameCallAndSavesIt() {
        service = batchService(true);
        givenMainWithSteps(2);
        when(aiClient.chat(any())).thenReturn(AiResponse.builder().content("{\"styleGuide\":\"one kitchen\","
                + "\"dishContext\":\"Part of making Egg Curry\",\"scenePrompt\":\"empty kadai on granite\",\"prompts\":["
                + "{\"id\":\"m1\",\"imagePrompt\":\"prompt for m1\"},{\"id\":\"m2\",\"imagePrompt\":\"prompt for m2\"}]}").build());

        service.ensurePrompts(USER, RECIPE, List.of(MAIN));

        verify(aiClient, times(1)).chat(any());
        assertTrue(capturedRequests().get(0).getUserPrompt().contains("SCENE: also write dishContext and scenePrompt"));
        verify(visualizationService).saveScenePrompt(RECIPE, "empty kadai on granite", "Part of making Egg Curry");
    }

    @Test
    void sceneMissingButEveryPromptWritten_makesASceneOnlyCall_whileAnImageIsStillMissing() {
        service = batchService(true);
        givenMainWithSteps(1);
        storedPrompts.put(VisualizationKeyBuilder.build("100", "main-0"), "already written");
        when(aiClient.chat(any())).thenReturn(AiResponse.builder().content("{\"styleGuide\":\"one kitchen\","
                + "\"dishContext\":\"Part of making Egg Curry\",\"scenePrompt\":\"empty kadai\",\"prompts\":[]}").build());

        service.ensurePrompts(USER, RECIPE, List.of(MAIN));

        AiRequest request = capturedRequests().get(0);
        assertTrue(request.getUserPrompt().contains("prompts is an empty list"), request.getUserPrompt());
        assertTrue(request.getUserPrompt().contains("already written"), "the existing prompt is a style reference");
        verify(visualizationService).saveScenePrompt(RECIPE, "empty kadai", "Part of making Egg Curry");
    }

    @Test
    void sceneMissingButEveryImageDone_makesNoCall() {
        service = batchService(true);
        givenMainWithSteps(1);
        storedPrompts.put(VisualizationKeyBuilder.build("100", "main-0"), "already written");
        when(visualizationService.hasImage(anyString())).thenReturn(true);

        assertEquals(0, service.ensurePrompts(USER, RECIPE, List.of(MAIN)));

        verify(aiClient, never()).chat(any());
    }

    @Test
    void existingScenePrompt_isNotAskedForAgain_andLeadsTheStyleReferences() {
        service = batchService(true);
        givenMainWithSteps(1);
        com.processVisualisation.virtualKitchen.ai.asset.VisualizationAsset scene = new com.processVisualisation.virtualKitchen.ai.asset.VisualizationAsset();
        scene.setImagePrompt("the stored empty scene");
        when(visualizationService.findScene(RECIPE)).thenReturn(Optional.of(scene));
        answerWithPromptsFor("m1");

        service.ensurePrompts(USER, RECIPE, List.of(MAIN));

        String prompt = capturedRequests().get(0).getUserPrompt();
        assertTrue(prompt.contains("the stored empty scene"));
        assertTrue(!prompt.contains("SCENE:"), prompt);
        verify(visualizationService, never()).saveScenePrompt(any(), any(), any());
    }

    @Test
    void writesEveryPromptOfTheRecipeInOneCall() {
        givenMainWithSteps(3);
        givenPreparationWithFlows(1);
        answerWithPromptsFor("p1", "m1", "m2", "m3");

        int written = service.ensurePrompts(USER, RECIPE, List.of(MAIN, PREP));

        assertEquals(4, written);
        verify(aiClient, times(1)).chat(any());
        assertEquals("prompt for m1", storedPrompts.get(VisualizationKeyBuilder.build("100", "main-0")));
        assertEquals("prompt for m3", storedPrompts.get(VisualizationKeyBuilder.build("100", "main-2")));
        assertEquals("prompt for p1", storedPrompts.get(VisualizationKeyBuilder.buildFlow("100", "flow-0")));
        verify(queueService).executeBounded(eq(USER), eq(AiCapability.TEXT_TO_TEXT), any(), any(),
                eq(VisualizationPromptBatchService.CORRELATION_TYPE), eq("100"), any());
    }

    @Test
    void targetsThatAlreadyHaveAPromptAreSkipped_andServeAsStyleReferences() {
        givenMainWithSteps(2);
        storedPrompts.put(VisualizationKeyBuilder.build("100", "main-0"), "existing kadai prompt");
        answerWithPromptsFor("m1");

        service.ensurePrompts(USER, RECIPE, List.of(MAIN));

        AiRequest request = capturedRequests().get(0);
        assertTrue(request.getUserPrompt().contains("existing kadai prompt"), "existing prompt is a style reference");
        assertTrue(request.getUserPrompt().contains("ids: m1."), request.getUserPrompt());
        assertEquals("existing kadai prompt", storedPrompts.get(VisualizationKeyBuilder.build("100", "main-0")));
        assertEquals("prompt for m1", storedPrompts.get(VisualizationKeyBuilder.build("100", "main-1")));
    }

    @Test
    void nothingMissing_makesNoCall() {
        givenMainWithSteps(1);
        storedPrompts.put(VisualizationKeyBuilder.build("100", "main-0"), "already written");

        assertEquals(0, service.ensurePrompts(USER, RECIPE, List.of(MAIN)));

        verify(aiClient, never()).chat(any());
    }

    @Test
    void anIdLeftOutOfTheAnswer_isLeftForThePerStepFallback() {
        givenMainWithSteps(3);
        answerWithPromptsFor("m1", "m3", "m9");

        assertEquals(2, service.ensurePrompts(USER, RECIPE, List.of(MAIN)));

        assertEquals(null, storedPrompts.get(VisualizationKeyBuilder.build("100", "main-1")));
        assertEquals(2, storedPrompts.size(), "an unknown id is ignored");
    }

    @Test
    void aFailedCall_savesNothingAndDoesNotThrow() {
        givenMainWithSteps(2);
        when(aiClient.chat(any())).thenThrow(new RuntimeException("provider down"));

        assertEquals(0, service.ensurePrompts(USER, RECIPE, List.of(MAIN)));

        verify(visualizationService, never()).savePrompt(anyString(), anyString());
    }

    @Test
    void anUnreadableAnswer_savesNothing() {
        givenMainWithSteps(2);
        when(aiClient.chat(any())).thenReturn(AiResponse.builder().content("not json at all").build());

        assertEquals(0, service.ensurePrompts(USER, RECIPE, List.of(MAIN)));

        verify(visualizationService, never()).savePrompt(anyString(), anyString());
    }

    @Test
    void moreTargetsThanOneRequestHolds_areSplitAcrossRequests() {
        givenMainWithSteps(VisualizationPromptBatchService.MAX_TARGETS_PER_REQUEST + 2);
        when(aiClient.chat(any())).thenAnswer(inv -> promptsForRequestedIds(inv.getArgument(0)));

        int written = service.ensurePrompts(USER, RECIPE, List.of(MAIN));

        assertEquals(VisualizationPromptBatchService.MAX_TARGETS_PER_REQUEST + 2, written);
        List<AiRequest> requests = capturedRequests();
        assertEquals(2, requests.size());
        assertTrue(requests.get(1).getUserPrompt().contains("EXISTING PROMPTS OF THIS RECIPE"),
                "the second request matches the style of the first");
    }

    @Test
    void aProcessOfAnotherRecipe_isLeftOut() {
        givenMainWithSteps(1);
        Process foreign = process(PREP, ProcessType.MAIN);
        foreign.setRecipeId(999L);
        when(visualizationService.prepareVisualTargets(PREP)).thenReturn(new VisualPreparation(foreign,
                List.of(new VisualTarget("foreign-0", input("Foreign step"), null))));
        answerWithPromptsFor("m1");

        service.ensurePrompts(USER, RECIPE, List.of(MAIN, PREP));

        assertTrue(capturedRequests().get(0).getUserPrompt().contains("ids: m1."));
        assertEquals(1, storedPrompts.size());
    }

    @Test
    void siblingJobsOfOneRecipe_makeOneCallBetweenThem() throws Exception {
        givenMainWithSteps(2);
        givenPreparationWithFlows(1);
        CountDownLatch callStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(aiClient.chat(any())).thenAnswer(inv -> {
            callStarted.countDown();
            release.await(5, TimeUnit.SECONDS);
            return promptsForRequestedIds(inv.getArgument(0));
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> service.ensurePrompts(USER, RECIPE, List.of(MAIN, PREP)));
            assertTrue(callStarted.await(5, TimeUnit.SECONDS));
            var second = pool.submit(() -> service.ensurePrompts(USER, RECIPE, List.of(MAIN, PREP)));
            release.countDown();

            assertEquals(3, first.get(5, TimeUnit.SECONDS));
            assertEquals(0, second.get(5, TimeUnit.SECONDS), "the sibling finds every prompt already written");
        } finally {
            pool.shutdownNow();
        }
        verify(aiClient, times(1)).chat(any());
    }

    // --- helpers -------------------------------------------------------------------------------

    private void givenMainWithSteps(int count) {
        List<VisualTarget> targets = IntStream.range(0, count)
                .mapToObj(i -> new VisualTarget("main-" + i, input("Main step " + i), null))
                .toList();
        when(visualizationService.prepareVisualTargets(MAIN)).thenReturn(new VisualPreparation(process(MAIN, ProcessType.MAIN), targets));
    }

    private void givenPreparationWithFlows(int count) {
        List<VisualTarget> targets = IntStream.range(0, count)
                .mapToObj(i -> new VisualTarget("flow-" + i, null,
                        new FlowContext("Component " + i, List.of(input("Cut " + i), input("Marinate " + i)))))
                .toList();
        when(visualizationService.prepareVisualTargets(PREP)).thenReturn(new VisualPreparation(process(PREP, ProcessType.SUBPROCESS), targets));
    }

    private void answerWithPromptsFor(String... ids) {
        when(aiClient.chat(any())).thenReturn(response(List.of(ids)));
    }

    /** Answers with one prompt for every id the request asked for. */
    private static AiResponse promptsForRequestedIds(AiRequest request) {
        String prompt = request.getUserPrompt();
        String idList = prompt.substring(prompt.lastIndexOf("ids: ") + 5, prompt.length() - 1);
        return response(List.of(idList.split(", ")));
    }

    private static AiResponse response(List<String> ids) {
        String prompts = ids.stream()
                .map(id -> "{\"id\":\"" + id + "\",\"imagePrompt\":\"prompt for " + id + "\"}")
                .collect(Collectors.joining(","));
        return AiResponse.builder().content("{\"styleGuide\":\"one kitchen\",\"prompts\":[" + prompts + "]}").build();
    }

    private List<AiRequest> capturedRequests() {
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiClient, org.mockito.Mockito.atLeastOnce()).chat(captor.capture());
        return new ArrayList<>(captor.getAllValues());
    }

    private static Process process(long id, ProcessType type) {
        Process process = new Process();
        process.setId(id);
        process.setRecipeId(RECIPE);
        process.setType(type);
        return process;
    }

    private static RecipeProcessVisualizationInput input(String description) {
        return new RecipeProcessVisualizationInput("Cook", List.of(), List.of(), description, description + " done", "", "", "", null);
    }
}
