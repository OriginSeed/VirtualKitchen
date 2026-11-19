package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import com.processVisualisation.virtualKitchen.ai.platform.config.AiGenerationParamsProperties;
import com.processVisualisation.virtualKitchen.ai.platform.dispatch.AiClientResolver;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestOutcome;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestQueueService;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.response.AiJsonResponseSupport;
import com.processVisualisation.virtualKitchen.ai.provider.AiClient;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiRequest;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiResponse;
import com.processVisualisation.virtualKitchen.ai.recipe.RecipeProcessAiException;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutputSchema;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutputSchema.Field;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.RecipeStoryboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stage 1 of a storyboard: one text call reads the whole recipe and groups its nodes into panels. The answer is
 * checked by {@link StoryboardSpecValidator}; an unusable or imperfect answer gets one more try that is told what was
 * wrong. After that, invalid panel links are dropped (and reported as warnings), while a structurally unusable plan
 * fails the storyboard.
 */
@Service
public class StoryboardPlanner {

    private static final Logger log = LoggerFactory.getLogger(StoryboardPlanner.class);

    static final String CORRELATION_TYPE = "recipe-storyboard-plan";
    static final String SCHEMA_NAME = "recipe_storyboard_plan";

    private final AiRequestQueueService queueService;
    private final AiClientResolver clientResolver;
    private final AiJsonResponseSupport responseSupport;
    private final StoryboardPromptBuilder promptBuilder;
    private final Map<String, Object> responseSchema = buildResponseSchema();

    /** Sampling parameters; admin runtime overrides apply to the next request. Defaults when not injected (tests). */
    private AiGenerationParamsProperties generationParams = new AiGenerationParamsProperties();

    public StoryboardPlanner(AiRequestQueueService queueService, AiClientResolver clientResolver,
                             AiJsonResponseSupport responseSupport, StoryboardPromptBuilder promptBuilder) {
        this.queueService = queueService;
        this.clientResolver = clientResolver;
        this.responseSupport = responseSupport;
        this.promptBuilder = promptBuilder;
    }

    @Autowired
    void setGenerationParams(AiGenerationParamsProperties generationParams) {
        this.generationParams = generationParams;
    }

    /**
     * The planned storyboard.
     *
     * @param warnings panel links that were dropped because they stayed invalid after the retry
     * @param modelKey the text model that wrote the plan
     */
    public record Planned(String title, String dishContext, List<RecipeStoryboard.Panel> panels,
                          List<RecipeStoryboard.OmittedStep> omittedSteps, List<String> warnings, String modelKey) {
    }

    /**
     * @param correlationId the storyboard's id
     * @throws RecipeProcessAiException when no usable plan came back after the retry
     */
    public Planned plan(Long userId, String correlationId, StoryboardSource source, int maxPanels) {
        AiRequestOutcome<Planned> outcome = queueService.executeBounded(
                userId, AiCapability.TEXT_TO_TEXT, null, null, CORRELATION_TYPE, correlationId,
                selection -> {
                    AiClient client = clientResolver.resolveTextClient(selection.model());
                    String modelId = selection.model().getProviderModelId();
                    Attempt first = attempt(client, modelId, source, maxPanels, null);
                    Attempt chosen = first;
                    if (first.result() == null || !first.result().clean()) {
                        log.info("event=storyboard_plan_retried problems={}", first.problems().size());
                        Attempt second = attempt(client, modelId, source, maxPanels, first);
                        // A usable first answer is kept when the retry turned out worse.
                        chosen = usable(second) || !usable(first) ? second : first;
                    }
                    if (!usable(chosen)) {
                        throw new RecipeProcessAiException("Unable to plan a storyboard for this recipe: "
                                + String.join("; ", chosen.problems().stream().limit(5).toList()));
                    }
                    StoryboardSpecValidator.Result result = chosen.result();
                    return new Planned(text(chosen.plan().title()), text(chosen.plan().dishContext()), result.panels(),
                            result.omitted(), new ArrayList<>(result.linkProblems()), null);
                });
        Planned planned = outcome.value();
        return new Planned(planned.title(), planned.dishContext(), planned.panels(), planned.omittedSteps(),
                planned.warnings(), outcome.selection() == null ? null : outcome.selection().model().getKey());
    }

    private record Attempt(String rawContent, StoryboardPlan plan, StoryboardSpecValidator.Result result, List<String> problems) {
    }

    private static boolean usable(Attempt attempt) {
        return attempt.result() != null && attempt.result().usable();
    }

    private Attempt attempt(AiClient client, String modelId, StoryboardSource source, int maxPanels, Attempt previous) {
        String userPrompt = previous == null ? promptBuilder.buildPlanUserPrompt(source)
                : promptBuilder.buildPlanRetryPrompt(source, previous.rawContent(), previous.problems());
        AiRequest request = AiRequest.builder()
                .model(modelId)
                .systemPrompt(promptBuilder.buildPlanSystemPrompt(maxPanels))
                .cacheSystemPrompt(true)
                .userPrompt(userPrompt)
                .temperature(generationParams.getStoryboardPlan().getTemperature())
                .maxTokens(generationParams.getStoryboardPlan().getMaxTokens())
                .responseFormat("json_object")
                .responseSchema(responseSchema)
                .responseSchemaName(SCHEMA_NAME)
                .operation(previous == null ? "STORYBOARD_PLAN" : "STORYBOARD_PLAN_RETRY")
                .build();
        long startNs = System.nanoTime();
        AiResponse response = client.chat(request);
        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        String content = response == null ? null : response.getContent();

        if (!StringUtils.hasText(content)) {
            List<String> problems = List.of("AI response content is empty");
            responseSupport.persistAIResponse(CORRELATION_TYPE, userPrompt, response, false, durationMs, problems);
            return new Attempt(content, null, null, problems);
        }
        try {
            StoryboardPlan plan = responseSupport.readValue(content, StoryboardPlan.class);
            StoryboardSpecValidator.Result result = StoryboardSpecValidator.validate(plan, source, maxPanels);
            responseSupport.persistAIResponse(CORRELATION_TYPE, userPrompt, response, result.usable(), durationMs,
                    result.allProblems());
            return new Attempt(content, plan, result, result.allProblems());
        } catch (Exception e) {
            List<String> problems = List.of("Invalid JSON format: " + e.getMessage());
            responseSupport.persistAIResponse(CORRELATION_TYPE, userPrompt, response, false, durationMs, problems);
            return new Attempt(content, null, null, problems);
        }
    }

    private static Map<String, Object> buildResponseSchema() {
        Map<String, Object> strings = RecipeProcessOutputSchema.arrayOf(RecipeProcessOutputSchema.string());
        Map<String, Object> panel = RecipeProcessOutputSchema.object(List.of(
                new Field("panelId", RecipeProcessOutputSchema.string(), true),
                new Field("sequence", Map.of("type", "integer"), true),
                new Field("group", RecipeProcessOutputSchema.enumOf(List.of(StoryboardSource.PREPARATION, StoryboardSource.MAIN)), true),
                new Field("component", RecipeProcessOutputSchema.string(), false),
                new Field("description", RecipeProcessOutputSchema.string(), true),
                new Field("sourceStepIds", strings, true),
                new Field("imagePrompt", RecipeProcessOutputSchema.string(), true),
                new Field("dependsOn", strings, true),
                new Field("referencePanels", strings, true),
                new Field("ingredients", strings, true),
                new Field("equipment", strings, true),
                new Field("expectedState", RecipeProcessOutputSchema.string(), true)));
        Map<String, Object> omitted = RecipeProcessOutputSchema.object(List.of(
                new Field("stepId", RecipeProcessOutputSchema.string(), true),
                new Field("reason", RecipeProcessOutputSchema.string(), true)));
        return RecipeProcessOutputSchema.object(List.of(
                new Field("title", RecipeProcessOutputSchema.string(), true),
                new Field("dishContext", RecipeProcessOutputSchema.string(), false),
                new Field("panels", RecipeProcessOutputSchema.arrayOf(panel), true),
                new Field("omittedSteps", RecipeProcessOutputSchema.arrayOf(omitted), false)));
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
