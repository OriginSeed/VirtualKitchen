package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import com.processVisualisation.virtualKitchen.ai.platform.config.AiGenerationParamsProperties;
import com.processVisualisation.virtualKitchen.ai.platform.dispatch.AiClientResolver;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestQueueService;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.response.AiJsonResponseSupport;
import com.processVisualisation.virtualKitchen.ai.provider.AiClient;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiRequest;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiResponse;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutputSchema;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutputSchema.Field;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.RecipeStoryboard;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationImageProperties;
import com.processVisualisation.virtualKitchen.common.logging.FailureLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Stage 2 of a storyboard: a second text call turns the planned panels into the one prompt the combined image is
 * requested with. The grid is decided here, not by the model. A prompt that does not name every panel gets one more
 * try; if that fails too (or the call itself fails), the prompt is assembled from the panels by a fixed template, so
 * this stage never costs a storyboard a panel or the storyboard itself.
 */
@Service
public class StoryboardMasterPromptWriter {

    private static final Logger log = LoggerFactory.getLogger(StoryboardMasterPromptWriter.class);

    static final String CORRELATION_TYPE = "recipe-storyboard-prompt";
    static final String SCHEMA_NAME = "recipe_storyboard_master_prompt";

    private final AiRequestQueueService queueService;
    private final AiClientResolver clientResolver;
    private final AiJsonResponseSupport responseSupport;
    private final StoryboardPromptBuilder promptBuilder;
    private final Map<String, Object> responseSchema = RecipeProcessOutputSchema.object(List.of(
            new Field("masterPrompt", RecipeProcessOutputSchema.string(), true)));

    /** Sampling parameters; admin runtime overrides apply to the next request. Defaults when not injected (tests). */
    private AiGenerationParamsProperties generationParams = new AiGenerationParamsProperties();

    public StoryboardMasterPromptWriter(AiRequestQueueService queueService, AiClientResolver clientResolver,
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

    /** The model's answer. */
    record Output(String masterPrompt) {
    }

    /**
     * @param prompt the image prompt
     * @param fromTemplate true when the text model delivered none and the fixed template was used
     */
    public record Written(String prompt, boolean fromTemplate) {
    }

    /** Never throws: the template stands in for any failure. */
    public Written write(Long userId, RecipeStoryboard storyboard, StoryboardLayout layout,
                         VisualizationImageProperties.Scene style) {
        int panels = storyboard.getPanels().size();
        String userPrompt = promptBuilder.buildMasterUserPrompt(storyboard, layout, style);
        try {
            String written = queueService.executeBounded(
                    userId, AiCapability.TEXT_TO_TEXT, null, null, CORRELATION_TYPE, storyboard.getId(),
                    selection -> {
                        AiClient client = clientResolver.resolveTextClient(selection.model());
                        String modelId = selection.model().getProviderModelId();
                        Answer first = attempt(client, modelId, userPrompt, panels, false);
                        if (first.problems().isEmpty()) return first.prompt();
                        Answer second = attempt(client, modelId,
                                promptBuilder.buildMasterRetryPrompt(userPrompt, first.problems()), panels, true);
                        return second.problems().isEmpty() ? second.prompt() : null;
                    }).value();
            if (StringUtils.hasText(written)) {
                return new Written(written.trim(), false);
            }
            log.warn("event=storyboard_master_prompt_unusable storyboardId={} fallback=template", storyboard.getId());
        } catch (Exception e) {
            FailureLogger.logFailure(log, "storyboard_master_prompt_failed", e,
                    "storyboardId=" + storyboard.getId() + " fallback=template");
        }
        return new Written(promptBuilder.buildTemplateMasterPrompt(storyboard, layout, style), true);
    }

    private record Answer(String prompt, List<String> problems) {
    }

    private Answer attempt(AiClient client, String modelId, String userPrompt, int panels, boolean retry) {
        AiRequest request = AiRequest.builder()
                .model(modelId)
                .systemPrompt(promptBuilder.buildMasterSystemPrompt())
                .cacheSystemPrompt(true)
                .userPrompt(userPrompt)
                .temperature(generationParams.getStoryboardPrompt().getTemperature())
                .maxTokens(generationParams.getStoryboardPrompt().getMaxTokens())
                .responseFormat("json_object")
                .responseSchema(responseSchema)
                .responseSchemaName(SCHEMA_NAME)
                .operation(retry ? "STORYBOARD_MASTER_PROMPT_RETRY" : "STORYBOARD_MASTER_PROMPT")
                .build();
        long startNs = System.nanoTime();
        AiResponse response = client.chat(request);
        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        String content = response == null ? null : response.getContent();

        String prompt = null;
        List<String> problems;
        if (!StringUtils.hasText(content)) {
            problems = List.of("AI response content is empty");
        } else {
            try {
                Output output = responseSupport.readValue(content, Output.class);
                prompt = output == null ? null : output.masterPrompt();
                problems = promptBuilder.checkMasterPrompt(prompt, panels);
            } catch (Exception e) {
                problems = List.of("Invalid JSON format: " + e.getMessage());
            }
        }
        responseSupport.persistAIResponse(CORRELATION_TYPE, userPrompt, response, problems.isEmpty(), durationMs, problems);
        return new Answer(prompt, problems);
    }
}
