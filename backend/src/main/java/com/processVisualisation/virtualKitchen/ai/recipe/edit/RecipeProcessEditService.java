package com.processVisualisation.virtualKitchen.ai.recipe.edit;

import com.processVisualisation.virtualKitchen.ai.platform.config.AiGenerationParamsProperties;
import com.processVisualisation.virtualKitchen.ai.platform.dispatch.AiClientResolver;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestOutcome;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestQueueService;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.response.AiJsonResponseSupport;
import com.processVisualisation.virtualKitchen.ai.platform.routing.FallbackReason;
import com.processVisualisation.virtualKitchen.ai.platform.routing.ModelSelectionOutcome;
import com.processVisualisation.virtualKitchen.ai.provider.AiClient;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiRequest;
import com.processVisualisation.virtualKitchen.ai.provider.dto.AiResponse;
import com.processVisualisation.virtualKitchen.ai.recipe.RecipeProcessAiException;
import com.processVisualisation.virtualKitchen.ai.recipe.edit.dto.EditTargetProcessDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.edit.dto.RecipeProcessEditResultDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessGenerationService;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.ProcessGenerationMode;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.RecipeProcessGenerationResultDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.model.RecipeProcessGenerationStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.function.Consumer;

/**
 * Orchestrates an AI EDIT of one existing process: the model receives the current process and the user's
 * instruction and returns a minimal list of edit operations ({@link RecipeProcessEditPromptBuilder}), which are
 * validated against that process ({@link RecipeProcessEditValidator}) with one retry-with-feedback, exactly like
 * {@link RecipeProcessGenerationService}. Nothing is persisted: the frontend applies the operations to the working
 * session once the user accepts them.
 */
@Service
public class RecipeProcessEditService {

    private static final Logger logger = LoggerFactory.getLogger(RecipeProcessEditService.class);
    private static final String CONTEXT = "process-edit";

    private final AiRequestQueueService queueService;
    private final AiClientResolver clientResolver;
    private final RecipeProcessEditPromptBuilder promptBuilder;
    private final RecipeProcessEditValidator validator;
    private final RecipeProcessEditOutputSchema outputSchema;
    private final AiJsonResponseSupport responseSupport;

    public RecipeProcessEditService(
            AiRequestQueueService queueService,
            AiClientResolver clientResolver,
            RecipeProcessEditPromptBuilder promptBuilder,
            RecipeProcessEditValidator validator,
            RecipeProcessEditOutputSchema outputSchema,
            AiJsonResponseSupport responseSupport
    ) {
        this.queueService = queueService;
        this.clientResolver = clientResolver;
        this.promptBuilder = promptBuilder;
        this.validator = validator;
        this.outputSchema = outputSchema;
        this.responseSupport = responseSupport;
    }

    /** Sampling parameters; admin runtime overrides apply to the next request. Defaults when not injected (tests). */
    private AiGenerationParamsProperties generationParams = new AiGenerationParamsProperties();

    @Autowired
    void setGenerationParams(AiGenerationParamsProperties generationParams) {
        this.generationParams = generationParams;
    }

    /**
     * @param instruction    the user's natural-language change
     * @param targetProcess  the process being changed, as serialised by the frontend
     * @param selectedNodeId the node selected in the editor, or null
     */
    public RecipeProcessGenerationResultDTO edit(
            Long userId, String instruction, EditTargetProcessDTO targetProcess, String selectedNodeId,
            String clientRequestId, Consumer<RecipeProcessGenerationStage> onStage
    ) {
        onStage.accept(RecipeProcessGenerationStage.BUILDING_PROMPT);
        RecipeProcessEditContext context = RecipeProcessEditContext.of(targetProcess, selectedNodeId);

        AiRequestOutcome<RecipeProcessEditResultDTO> outcome = queueService.executeBounded(
                userId,
                AiCapability.TEXT_TO_TEXT,
                null,
                clientRequestId,
                CONTEXT,
                null,
                selection -> runAttempts(context, instruction, selection, onStage)
        );

        ModelSelectionOutcome selection = outcome.selection();
        return RecipeProcessGenerationResultDTO.builder()
                .mode(ProcessGenerationMode.EDIT)
                .edit(outcome.value())
                .modelUsed(selection.model().getKey())
                .modelTier(selection.model().getTier().name())
                .usedFallback(selection.usedFallback())
                .fallbackReason(selection.fallbackReason() == FallbackReason.NONE ? null : selection.fallbackReason().name())
                .build();
    }

    private RecipeProcessEditResultDTO runAttempts(
            RecipeProcessEditContext context, String instruction, ModelSelectionOutcome selection,
            Consumer<RecipeProcessGenerationStage> onStage
    ) {
        AiClient client = clientResolver.resolveTextClient(selection.model());
        String modelId = selection.model().getProviderModelId();

        onStage.accept(RecipeProcessGenerationStage.CALLING_MODEL);
        AttemptResult firstAttempt = runAttempt(client, modelId, context, instruction, null);
        onStage.accept(RecipeProcessGenerationStage.VALIDATING_RESPONSE);
        if (firstAttempt.valid()) {
            logValidationCompleted(1, firstAttempt.result());
            return firstAttempt.result();
        }

        logValidationFailed(1, true, firstAttempt.errors());
        onStage.accept(RecipeProcessGenerationStage.RETRYING);
        AttemptResult secondAttempt = runAttempt(client, modelId, context, instruction, firstAttempt);
        if (secondAttempt.valid()) {
            logValidationCompleted(2, secondAttempt.result());
            return secondAttempt.result();
        }

        logValidationFailed(2, false, secondAttempt.errors());
        throw new RecipeProcessAiException("Unable to apply the requested change: " + String.join("; ", secondAttempt.errors()));
    }

    private AttemptResult runAttempt(
            AiClient client, String modelId, RecipeProcessEditContext context, String instruction, AttemptResult prevAttempt
    ) {
        String userPrompt = prevAttempt != null
                ? promptBuilder.buildRetryPrompt(context, instruction, prevAttempt.rawContent(), prevAttempt.errors())
                : promptBuilder.buildInitialPrompt(context, instruction);

        AiRequest request = AiRequest.builder()
                .model(modelId)
                .systemPrompt(promptBuilder.buildSystemPrompt())
                .cacheSystemPrompt(true)
                .userPrompt(userPrompt)
                .temperature(generationParams.getProcessEdit().getTemperature())
                .maxTokens(generationParams.getProcessEdit().getMaxTokens())
                .responseFormat("json_object")
                .responseSchema(outputSchema.jsonSchema())
                .responseSchemaName(RecipeProcessEditOutputSchema.SCHEMA_NAME)
                .operation(prevAttempt != null ? "RECIPE_EDIT_FLOW_RETRY" : "RECIPE_EDIT_FLOW")
                .build();

        long startNs = System.nanoTime();
        AiResponse response = client.chat(request);
        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        String content = response == null ? null : response.getContent();

        if (!StringUtils.hasText(content)) {
            List<String> errors = List.of("AI response content is empty");
            responseSupport.persistAIResponse(CONTEXT, instruction, response, false, durationMs, errors);
            return AttemptResult.invalid(content, errors);
        }

        try {
            RecipeProcessEditOutput output = responseSupport.readValue(content, RecipeProcessEditOutput.class);
            RecipeProcessEditValidator.Result validation = validator.validate(output, context);
            if (!validation.valid()) {
                responseSupport.persistAIResponse(CONTEXT, instruction, response, false, durationMs, validation.errors());
                return AttemptResult.invalid(content, validation.errors());
            }

            boolean asking = StringUtils.hasText(output.clarification());
            RecipeProcessEditResultDTO result = RecipeProcessEditResultDTO.builder()
                    .targetProcessId(context.processId())
                    .summary(output.summary())
                    .clarification(asking ? output.clarification().trim() : null)
                    .operations(validation.operations())
                    .build();
            responseSupport.persistAIResponse(CONTEXT, instruction, response, true, durationMs, List.of());
            return AttemptResult.valid(content, result);
        } catch (Exception ex) {
            List<String> errors = List.of("Invalid JSON format: " + ex.getMessage());
            responseSupport.persistAIResponse(CONTEXT, instruction, response, false, durationMs, errors);
            return AttemptResult.invalid(content, errors);
        }
    }

    private void logValidationCompleted(int attempt, RecipeProcessEditResultDTO result) {
        logger.debug("event=process_edit_validation_completed attempt={} promptVersion={} operations={} clarification={}",
                attempt, RecipeProcessEditPromptBuilder.PROMPT_VERSION,
                result.getOperations() == null ? 0 : result.getOperations().size(), result.getClarification() != null);
    }

    private void logValidationFailed(int attempt, boolean willRetry, List<String> errors) {
        logger.warn("event=process_validation_failed mode=EDIT attempt={} willRetry={} promptVersion={} errorCount={}",
                attempt, willRetry, RecipeProcessEditPromptBuilder.PROMPT_VERSION, errors.size());
        // The messages can quote fragments of the model output, so they stay at DEBUG.
        logger.debug("event=process_validation_errors mode=EDIT attempt={} errors={}", attempt, errors);
    }

    private record AttemptResult(boolean valid, String rawContent, RecipeProcessEditResultDTO result, List<String> errors) {
        static AttemptResult valid(String rawContent, RecipeProcessEditResultDTO result) {
            return new AttemptResult(true, rawContent, result, List.of());
        }

        static AttemptResult invalid(String rawContent, List<String> errors) {
            return new AttemptResult(false, rawContent, null, errors);
        }
    }
}
