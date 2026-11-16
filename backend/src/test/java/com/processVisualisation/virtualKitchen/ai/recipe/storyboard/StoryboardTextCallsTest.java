package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiRequest;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiResponse;
import com.processVisualisation.virtualKitchen.ai.recipe.RecipeProcessAiException;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.RecipeStoryboard;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationImageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.processVisualisation.virtualKitchen.ai.recipe.storyboard.StoryboardTestFixtures.alias;
import static com.processVisualisation.virtualKitchen.ai.recipe.storyboard.StoryboardTestFixtures.layeredPlan;
import static com.processVisualisation.virtualKitchen.ai.recipe.storyboard.StoryboardTestFixtures.panel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The storyboard's two text calls: planning the panels (stage 1) and writing the image prompt (stage 2). */
class StoryboardTextCallsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final StoryboardPromptBuilder promptBuilder = new StoryboardPromptBuilder();
    private final VisualizationImageProperties.Scene style = new VisualizationImageProperties.Scene();

    private AiClient aiClient;
    private AiRequestQueueService queueService;
    private StoryboardPlanner planner;
    private StoryboardMasterPromptWriter writer;
    private StoryboardSource source;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        aiClient = mock(AiClient.class);
        queueService = mock(AiRequestQueueService.class);
        AiClientResolver clientResolver = mock(AiClientResolver.class);
        AiJsonResponseSupport responseSupport = mock(AiJsonResponseSupport.class);
        when(clientResolver.resolveTextClient(any())).thenReturn(aiClient);
        when(responseSupport.readValue(any(), any())).thenAnswer(invocation ->
                mapper.readValue((String) invocation.getArgument(0), (Class<Object>) invocation.getArgument(1)));

        ModelDefinition model = new ModelDefinition();
        model.setKey("test-text");
        model.setProviderModelId("provider-text");
        ModelSelectionOutcome selection = new ModelSelectionOutcome(model, false, FallbackReason.NONE, null);
        // The queue runs the work at once, on the caller's thread.
        when(queueService.executeBounded(eq(7L), eq(AiCapability.TEXT_TO_TEXT), isNull(), isNull(), any(), any(), any(AiWork.class)))
                .thenAnswer(invocation -> AiRequestOutcome.fresh("request-1",
                        ((AiWork<Object>) invocation.getArgument(6)).run(selection), selection, null));

        planner = new StoryboardPlanner(queueService, clientResolver, responseSupport, promptBuilder);
        writer = new StoryboardMasterPromptWriter(queueService, clientResolver, responseSupport, promptBuilder);
        source = StoryboardTestFixtures.source(StoryboardTestFixtures.layeredRecipe());
    }

    // ------------------------------------------------------------------------------------------- stage 1: plan

    @Test
    void aValidPlan_isTakenFromOneCall_withTheRealNodeIdsAndTheModelThatWroteIt() throws Exception {
        answers(json(layeredPlan(source)));

        StoryboardPlanner.Planned planned = planner.plan(7L, "sb-1", source, 16);

        assertEquals("Biryani", planned.title());
        assertEquals("A layered rice dish", planned.dishContext());
        assertEquals(7, planned.panels().size());
        assertEquals(List.of("coat", "rest"), planned.panels().get(1).getSourceStepIds());
        assertTrue(planned.warnings().isEmpty());
        assertEquals("test-text", planned.modelKey());

        AiRequest request = requests(1).get(0);
        assertEquals("STORYBOARD_PLAN", request.getOperation());
        assertEquals("recipe_storyboard_plan", request.getResponseSchemaName());
        assertEquals("provider-text", request.getModel());
        assertTrue(request.getUserPrompt().contains(source.json()));
        verify(queueService).executeBounded(eq(7L), eq(AiCapability.TEXT_TO_TEXT), isNull(), isNull(),
                eq("recipe-storyboard-plan"), eq("sb-1"), any(AiWork.class));
    }

    @Test
    void invalidJson_isRetriedOnce_andTheRetryIsToldWhatWasWrong() throws Exception {
        answers("this is not json", json(layeredPlan(source)));

        StoryboardPlanner.Planned planned = planner.plan(7L, "sb-1", source, 16);

        assertEquals(7, planned.panels().size());
        List<AiRequest> requests = requests(2);
        assertEquals("STORYBOARD_PLAN_RETRY", requests.get(1).getOperation());
        assertTrue(requests.get(1).getUserPrompt().contains("Invalid JSON format"));
        assertTrue(requests.get(1).getUserPrompt().contains("this is not json"));
    }

    @Test
    void aPlanThatStaysUnusable_failsThePlanning() throws Exception {
        StoryboardPlan missingSteps = new StoryboardPlan("x", "", List.of(
                panel("P1", 1, "MAIN", List.of(alias(source, "heat")), List.of(), List.of())), List.of());
        answers(json(missingSteps), "");

        RecipeProcessAiException error = assertThrows(RecipeProcessAiException.class,
                () -> planner.plan(7L, "sb-1", source, 16));

        assertTrue(error.getMessage().startsWith("Unable to plan a storyboard for this recipe"), error.getMessage());
        requests(2);
    }

    @Test
    void invalidLinks_getOneMoreTry_andAreDroppedWithAWarningWhenTheyStay() throws Exception {
        List<StoryboardPlan.Panel> panels = new ArrayList<>(layeredPlan(source).panels());
        panels.set(0, panel("P1", 1, "PREPARATION", List.of(alias(source, "mix")), List.of("P9"), List.of()));
        String badLinks = json(new StoryboardPlan("Biryani", "", panels, List.of()));
        answers(badLinks, badLinks);

        StoryboardPlanner.Planned planned = planner.plan(7L, "sb-1", source, 16);

        assertEquals(7, planned.panels().size());
        assertTrue(planned.panels().get(0).getDependsOn().isEmpty());
        assertEquals(1, planned.warnings().size());
        assertTrue(planned.warnings().get(0).contains("unknown panel \"P9\""), planned.warnings().toString());
        requests(2);
    }

    @Test
    void aUsableFirstPlan_isKept_whenTheRetryComesBackWorse() throws Exception {
        List<StoryboardPlan.Panel> panels = new ArrayList<>(layeredPlan(source).panels());
        panels.set(0, panel("P1", 1, "PREPARATION", List.of(alias(source, "mix")), List.of("P9"), List.of()));
        answers(json(new StoryboardPlan("Biryani", "", panels, List.of())), "{\"title\":\"x\",\"panels\":[]}");

        StoryboardPlanner.Planned planned = planner.plan(7L, "sb-1", source, 16);

        assertEquals(7, planned.panels().size());
        assertEquals(1, planned.warnings().size());
    }

    // ---------------------------------------------------------------------------------- stage 2: master prompt

    @Test
    void theMasterPrompt_isWrittenFromTheSpecification_inOneCall() throws Exception {
        RecipeStoryboard storyboard = StoryboardTestFixtures.storyboard(4);
        String prompt = "One image in a 2x2 grid. Panel 1: a. Panel 2: b. Panel 3: c. Panel 4: d. No text.";
        answers(mapper.writeValueAsString(Map.of("masterPrompt", prompt)));

        StoryboardMasterPromptWriter.Written written = writer.write(7L, storyboard, StoryboardLayout.of(4), style);

        assertEquals(prompt, written.prompt());
        assertFalse(written.fromTemplate());
        AiRequest request = requests(1).get(0);
        assertEquals("STORYBOARD_MASTER_PROMPT", request.getOperation());
        assertTrue(request.getUserPrompt().contains("\"grid\":{\"rows\":2,\"columns\":2,\"panels\":4}"));
        verify(queueService).executeBounded(eq(7L), eq(AiCapability.TEXT_TO_TEXT), isNull(), isNull(),
                eq("recipe-storyboard-prompt"), eq("sb-1"), any(AiWork.class));
    }

    @Test
    void aMasterPromptThatLosesAPanel_isAskedForAgain_andThenReplacedByTheTemplate() throws Exception {
        RecipeStoryboard storyboard = StoryboardTestFixtures.storyboard(4);
        String incomplete = mapper.writeValueAsString(Map.of("masterPrompt", "Panel 1: a. Panel 2: b. Panel 3: c."));
        answers(incomplete, incomplete);

        StoryboardMasterPromptWriter.Written written = writer.write(7L, storyboard, StoryboardLayout.of(4), style);

        assertTrue(written.fromTemplate());
        assertTrue(promptBuilder.checkMasterPrompt(written.prompt(), 4).isEmpty());
        assertTrue(written.prompt().contains("Photo of action 4"));
        List<AiRequest> requests = requests(2);
        assertEquals("STORYBOARD_MASTER_PROMPT_RETRY", requests.get(1).getOperation());
        assertTrue(requests.get(1).getUserPrompt().contains("no line for panel 4"));
    }

    @Test
    void aSecondAttemptThatNamesEveryPanel_isUsed() throws Exception {
        RecipeStoryboard storyboard = StoryboardTestFixtures.storyboard(2);
        answers("not json", mapper.writeValueAsString(Map.of("masterPrompt", "Panel 1: a. Panel 2: b.")));

        StoryboardMasterPromptWriter.Written written = writer.write(7L, storyboard, StoryboardLayout.of(2), style);

        assertEquals("Panel 1: a. Panel 2: b.", written.prompt());
        assertFalse(written.fromTemplate());
    }

    @Test
    void aFailingTextCall_neverCostsTheStoryboard_theTemplateStandsIn() {
        RecipeStoryboard storyboard = StoryboardTestFixtures.storyboard(3);
        when(aiClient.chat(any())).thenThrow(new IllegalStateException("provider down"));

        StoryboardMasterPromptWriter.Written written = writer.write(7L, storyboard, StoryboardLayout.of(3), style);

        assertTrue(written.fromTemplate());
        assertTrue(promptBuilder.checkMasterPrompt(written.prompt(), 3).isEmpty());
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private void answers(String first, String... more) {
        // The responses are made first: a mock cannot be stubbed while another stubbing is open.
        AiResponse firstResponse = response(first);
        List<AiResponse> later = new ArrayList<>();
        for (String content : more) {
            later.add(response(content));
        }
        org.mockito.stubbing.OngoingStubbing<AiResponse> stubbing = when(aiClient.chat(any())).thenReturn(firstResponse);
        for (AiResponse response : later) {
            stubbing = stubbing.thenReturn(response);
        }
    }

    private static AiResponse response(String content) {
        AiResponse response = mock(AiResponse.class);
        when(response.getContent()).thenReturn(content);
        return response;
    }

    private List<AiRequest> requests(int expected) {
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiClient, times(expected)).chat(captor.capture());
        return captor.getAllValues();
    }

    private String json(StoryboardPlan plan) throws Exception {
        return mapper.writeValueAsString(plan);
    }
}
