package com.processVisualisation.virtualKitchen.ai.recipe.generation;

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
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedRecipeProcessDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.ProcessGenerationMode;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.RecipeProcessGenerationResultDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.model.RecipeProcessGenerationStage;
import com.processVisualisation.virtualKitchen.recipe.validation.ProcessValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.function.Consumer;

/**
 * Orchestrates AI-driven generation of a semantic Process structure (MAIN
 * process + subprocesses) from free-form recipe text, using the AI queue,
 * model selection and retry-with-feedback against
 * {@link RecipeProcessGenerationPromptBuilder}'s schema and
 * {@link RecipeProcessGenerationValidator}. It never persists the result itself — the generated structure is only ever
 * returned to the frontend, which loads it into the current Recipe working
 * session for the user to review/edit before an explicit Save.
 */
@Service
public class RecipeProcessGenerationService {

    private static final Logger logger = LoggerFactory.getLogger(RecipeProcessGenerationService.class);

    private final AiRequestQueueService queueService;
    private final AiClientResolver clientResolver;
    private final RecipeProcessGenerationPromptBuilder promptBuilder;
    private final RecipeProcessGenerationValidator validator;
    private final AiJsonResponseSupport responseSupport;
    private final RecipeProcessOutputSchema outputSchema;
    private final RecipeProcessOutputNormalizer outputNormalizer;

    public RecipeProcessGenerationService(
            AiRequestQueueService queueService,
            AiClientResolver clientResolver,
            RecipeProcessGenerationPromptBuilder promptBuilder,
            RecipeProcessGenerationValidator validator,
            AiJsonResponseSupport responseSupport,
            RecipeProcessOutputSchema outputSchema,
            RecipeProcessOutputNormalizer outputNormalizer
    ) {
        this.queueService = queueService;
        this.clientResolver = clientResolver;
        this.promptBuilder = promptBuilder;
        this.validator = validator;
        this.responseSupport = responseSupport;
        this.outputSchema = outputSchema;
        this.outputNormalizer = outputNormalizer;
    }

    /** Sampling parameters; admin runtime overrides apply to the next request. Defaults when not injected (tests). */
    private AiGenerationParamsProperties generationParams = new AiGenerationParamsProperties();

    @Autowired
    void setGenerationParams(AiGenerationParamsProperties generationParams) {
        this.generationParams = generationParams;
    }

    public RecipeProcessGenerationResultDTO generate(Long userId, String recipeText, String clientRequestId) {
        return generate(userId, recipeText, clientRequestId, stage -> { });
    }

    /**
     * Same as {@link #generate(Long, String, String)}, but additionally reports the generation
     * pipeline's internal progress milestones via {@code onStage} — used by
     * {@link RecipeProcessGenerationJobService} to back a client-pollable job's progress percentage.
     */
    public RecipeProcessGenerationResultDTO generate(
            Long userId, String recipeText, String clientRequestId, Consumer<RecipeProcessGenerationStage> onStage
    ) {
        onStage.accept(RecipeProcessGenerationStage.BUILDING_PROMPT);

        Prompts prompts = new Prompts(promptBuilder.buildSystemPrompt(), promptBuilder.buildInitialPrompt(recipeText),
                (raw, errors) -> promptBuilder.buildRetryPrompt(recipeText, raw, errors), outputSchema.jsonSchema(),
                "RECIPE_TO_FLOW", "process-generation", recipeText);
        AiRequestOutcome<RecipeProcessGenerationResultDTO> outcome = queueService.executeBounded(
                userId,
                AiCapability.TEXT_TO_TEXT,
                null,
                clientRequestId,
                "process-generation",
                null,
                selection -> runAttempts(prompts, selection, onStage)
        );
        return finish(outcome, ProcessGenerationMode.CREATE);
    }

    /**
     * Re-authors the cook's saved recipe as a corrected complete flow (REFINE). {@code currentRecipeJson} is the recipe
     * as the storyboard serializer writes it, with node aliases; {@code nodeIdByAlias} maps them back, so each refined
     * step that comes from exactly one of the cook's nodes keeps that node's id.
     */
    public RecipeProcessGenerationResultDTO refine(Long userId, String currentRecipeJson, String note,
                                                   java.util.Map<String, String> nodeIdByAlias, String clientRequestId,
                                                   Consumer<RecipeProcessGenerationStage> onStage) {
        onStage.accept(RecipeProcessGenerationStage.BUILDING_PROMPT);
        Prompts prompts = new Prompts(promptBuilder.buildRefineSystemPrompt(),
                promptBuilder.buildRefineInitialPrompt(currentRecipeJson, note),
                (raw, errors) -> promptBuilder.buildRefineRetryPrompt(currentRecipeJson, note, raw, errors),
                outputSchema.refineJsonSchema(), "RECIPE_REFINE", "process-refine", currentRecipeJson);
        AiRequestOutcome<RecipeProcessGenerationResultDTO> outcome = queueService.executeBounded(
                userId, AiCapability.TEXT_TO_TEXT, null, clientRequestId, "process-refine", null,
                selection -> runAttempts(prompts, selection, onStage));
        RecipeProcessGenerationResultDTO result = finish(outcome, ProcessGenerationMode.REFINE);
        mapSourceNodes(result, nodeIdByAlias);
        return result;
    }

    /** Each refined step's aliases become the id of the one cook's node it re-authors (first claim wins). */
    static void mapSourceNodes(RecipeProcessGenerationResultDTO result, java.util.Map<String, String> nodeIdByAlias) {
        java.util.Set<String> claimed = new java.util.HashSet<>();
        java.util.List<GeneratedRecipeProcessDTO> processes = new java.util.ArrayList<>();
        if (result.getSubprocesses() != null) processes.addAll(result.getSubprocesses());
        if (result.getMainProcess() != null) processes.add(result.getMainProcess());
        for (GeneratedRecipeProcessDTO process : processes) {
            if (process.getSteps() == null) continue;
            for (com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedRecipeStepDTO step : process.getSteps()) {
                java.util.List<String> aliases = step.getSourceAliases();
                step.setSourceAliases(null);
                if (aliases == null || aliases.size() != 1) continue;
                String nodeId = nodeIdByAlias.get(aliases.get(0));
                if (nodeId != null && claimed.add(nodeId)) step.setSourceNodeId(nodeId);
            }
        }
    }

    private RecipeProcessGenerationResultDTO finish(AiRequestOutcome<RecipeProcessGenerationResultDTO> outcome,
                                                    ProcessGenerationMode mode) {
        RecipeProcessGenerationResultDTO result = outcome.value();
        result.setMode(mode);
        ModelSelectionOutcome selection = outcome.selection();
        result.setModelUsed(selection.model().getKey());
        result.setModelTier(selection.model().getTier().name());
        result.setUsedFallback(selection.usedFallback());
        result.setFallbackReason(selection.fallbackReason() == FallbackReason.NONE ? null : selection.fallbackReason().name());
        return result;
    }

    /** Everything that differs between CREATE and REFINE attempts. */
    private record Prompts(String system, String initial, java.util.function.BiFunction<String, List<String>, String> retry,
                           java.util.Map<String, Object> schema, String operation, String context, String input) {
    }

    private RecipeProcessGenerationResultDTO runAttempts(
            Prompts prompts, ModelSelectionOutcome selection, Consumer<RecipeProcessGenerationStage> onStage
    ) {
        AiClient client = clientResolver.resolveTextClient(selection.model());
        String modelId = selection.model().getProviderModelId();

        onStage.accept(RecipeProcessGenerationStage.CALLING_MODEL);
        AttemptResult firstAttempt = runAttempt(client, modelId, prompts, null);
        onStage.accept(RecipeProcessGenerationStage.VALIDATING_RESPONSE);
        if (firstAttempt.valid()) {
            logValidationCompleted(1, firstAttempt.result());
            return firstAttempt.result();
        }

        logValidationFailed(1, true, firstAttempt.errors());
        onStage.accept(RecipeProcessGenerationStage.RETRYING);
        AttemptResult secondAttempt = runAttempt(client, modelId, prompts, firstAttempt);
        if (secondAttempt.valid()) {
            logValidationCompleted(2, secondAttempt.result());
            return secondAttempt.result();
        }

        logValidationFailed(2, false, secondAttempt.errors());
        String errorMessage = String.join("; ", secondAttempt.errors());
        throw new RecipeProcessAiException("Unable to generate a valid recipe process: " + errorMessage);
    }

    private AttemptResult runAttempt(AiClient client, String modelId, Prompts prompts, AttemptResult prevAttempt) {
        String userPrompt = prompts.input();
        String context = prompts.context();
        String refinedPrompt = prevAttempt != null
                ? prompts.retry().apply(prevAttempt.rawContent(), prevAttempt.errors())
                : prompts.initial();

        AiRequest request = AiRequest.builder()
                .model(modelId)
                .systemPrompt(prompts.system())
                .cacheSystemPrompt(true)
                .userPrompt(refinedPrompt)
                .temperature(generationParams.getProcessGeneration().getTemperature())
                .maxTokens(generationParams.getProcessGeneration().getMaxTokens())
                .responseFormat("json_object")
                .responseSchema(prompts.schema())
                .responseSchemaName(RecipeProcessOutputSchema.SCHEMA_NAME)
                .operation(prevAttempt != null ? prompts.operation() + "_RETRY" : prompts.operation())
                .build();

        long startNs = System.nanoTime();
        AiResponse response = client.chat(request);
        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        String content = response == null ? null : response.getContent();

        if (!StringUtils.hasText(content)) {
            List<String> errors = List.of("AI response content is empty");
            responseSupport.persistAIResponse(context, userPrompt, response, false, durationMs, errors);
            return AttemptResult.invalid(content, errors);
        }

        try {
            RecipeProcessOutput output = responseSupport.readValue(content, RecipeProcessOutput.class);
            // Cosmetic slips (aliases, uncatalogued values, fields the action doesn't take) are repaired here, so a
            // retry is only spent on real structural errors.
            RecipeProcessOutputNormalizer.Normalized normalized = outputNormalizer.normalize(output);
            GeneratedRecipeProcessDTO mainProcess = normalized.main();
            List<GeneratedRecipeProcessDTO> preparations = normalized.preparations();
            logRepairs(normalized.repairs());

            ProcessValidationResult validationResult = validator.validate(mainProcess, preparations);
            if (!validationResult.isValid()) {
                responseSupport.persistAIResponse(context, userPrompt, response, false, durationMs, validationResult.getErrors());
                return AttemptResult.invalid(content, validationResult.getErrors());
            }

            RecipeProcessGenerationResultDTO generated = RecipeProcessGenerationResultDTO.builder()
                    .mainProcess(mainProcess)
                    .subprocesses(preparations)
                    .repairs(normalized.repairs())
                    .warnings(validator.warnings(mainProcess, preparations))
                    .changes(output.changes() == null ? null : List.copyOf(output.changes()))
                    .build();
            responseSupport.persistAIResponse(context, userPrompt, response, true, durationMs, List.of());
            return AttemptResult.valid(content, generated);
        } catch (Exception ex) {
            List<String> errors = List.of("Invalid JSON format: " + ex.getMessage());
            responseSupport.persistAIResponse(context, userPrompt, response, false, durationMs, errors);
            return AttemptResult.invalid(content, errors);
        }
    }

    private void logRepairs(List<String> repairs) {
        if (repairs.isEmpty()) return;
        logger.info("event=process_output_repaired promptVersion={} repairCount={}", RecipeProcessGenerationPromptBuilder.PROMPT_VERSION, repairs.size());
        // The messages quote fragments of the model output, so they stay at DEBUG.
        logger.debug("event=process_output_repairs repairs={}", repairs);
    }

    private void logValidationCompleted(int attempt, RecipeProcessGenerationResultDTO result) {
        logger.debug("event=process_validation_completed attempt={} promptVersion={} subprocesses={}",
                attempt, RecipeProcessGenerationPromptBuilder.PROMPT_VERSION,
                result.getSubprocesses() == null ? 0 : result.getSubprocesses().size());
    }

    private void logValidationFailed(int attempt, boolean willRetry, List<String> errors) {
        logger.warn("event=process_validation_failed attempt={} willRetry={} promptVersion={} errorCount={}",
                attempt, willRetry, RecipeProcessGenerationPromptBuilder.PROMPT_VERSION, errors.size());
        // The messages can quote fragments of the model output, so they stay at DEBUG.
        logger.debug("event=process_validation_errors attempt={} errors={}", attempt, errors);
    }

    private record AttemptResult(boolean valid, String rawContent, RecipeProcessGenerationResultDTO result, List<String> errors) {
        static AttemptResult valid(String rawContent, RecipeProcessGenerationResultDTO result) {
            return new AttemptResult(true, rawContent, result, List.of());
        }

        static AttemptResult invalid(String rawContent, List<String> errors) {
            return new AttemptResult(false, rawContent, null, errors);
        }
    }
}
