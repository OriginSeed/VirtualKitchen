package com.processVisualisation.virtualKitchen.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.client.AIClient;
import com.processVisualisation.virtualKitchen.ai.dto.AIRequest;
import com.processVisualisation.virtualKitchen.ai.dto.AIResponse;
import com.processVisualisation.virtualKitchen.dto.RecipeExecutionEdgeDTO;
import com.processVisualisation.virtualKitchen.dto.RecipeExecutionStepDTO;
import com.processVisualisation.virtualKitchen.dto.RecipeFlowGenerationResponseDTO;
import com.processVisualisation.virtualKitchen.exception.RecipeFlowGenerationException;
import com.processVisualisation.virtualKitchen.service.recipe.RecipeFlowPromptBuilder;
import com.processVisualisation.virtualKitchen.service.recipe.RecipeFlowValidationResult;
import com.processVisualisation.virtualKitchen.service.recipe.RecipeValidator;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class RecipeGenerationService {

    private static final TypeReference<List<RecipeExecutionStepDTO>> LIST_OF_STEP_TYPE = new TypeReference<>() {
    };

    private static final TypeReference<List<RecipeExecutionEdgeDTO>> LIST_OF_EDGE_TYPE = new TypeReference<>() {
    };

    private final AIClient aiClient;
    private final RecipeFlowPromptBuilder promptBuilder;
    private final RecipeValidator recipeValidator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RecipeGenerationService(
            AIClient aiClient,
            RecipeFlowPromptBuilder promptBuilder,
            RecipeValidator recipeValidator
    ) {
        this.aiClient = aiClient;
        this.promptBuilder = promptBuilder;
        this.recipeValidator = recipeValidator;
    }

    public RecipeFlowGenerationResponseDTO generateFlow(String recipeText) {
        System.out.println("[RECIPE-GEN] Start generate flow");

        String firstPrompt = promptBuilder.buildInitialPrompt(recipeText);
        AttemptResult firstAttempt = runAttempt(firstPrompt);
        if (firstAttempt.valid()) {
            return firstAttempt.response();
        }

        System.out.println("[RECIPE-GEN] Validation failed on first attempt. Retrying once.");
        String retryPrompt = promptBuilder.buildRetryPrompt(recipeText, firstAttempt.rawContent(), firstAttempt.errors());
        AttemptResult secondAttempt = runAttempt(retryPrompt);
        if (secondAttempt.valid()) {
            return secondAttempt.response();
        }

        String errorMessage = String.join("; ", secondAttempt.errors());
        System.out.println("[RECIPE-GEN] Failed after retry. errors=" + errorMessage);
        throw new RecipeFlowGenerationException("Unable to generate valid recipe flow: " + errorMessage);
    }

    private AttemptResult runAttempt(String userPrompt) {
        AIRequest request = AIRequest.builder()
                .systemPrompt(promptBuilder.buildSystemPrompt())
                .userPrompt(userPrompt)
                .temperature(0.1d)
                .maxTokens(2000)
                .build();

        AIResponse response = aiClient.chat(request);
        String content = response == null ? null : response.getContent();

        if (!StringUtils.hasText(content)) {
            return AttemptResult.invalid(content, List.of("AI response content is empty"));
        }

        try {
            JsonNode root = parseJson(content);
            JsonNode stepsNode = root.path("steps");
            JsonNode edgesNode = root.path("edges");

            if (!stepsNode.isArray() || !edgesNode.isArray()) {
                return AttemptResult.invalid(content, List.of("Top-level JSON must contain arrays: steps and edges"));
            }

             List<RecipeExecutionStepDTO> steps = objectMapper.convertValue(stepsNode, LIST_OF_STEP_TYPE);
             List<RecipeExecutionEdgeDTO> edges = objectMapper.convertValue(edgesNode, LIST_OF_EDGE_TYPE);

             normalizeExecutionDataTypes(steps, edges);

             RecipeFlowValidationResult validationResult = recipeValidator.validate(steps, edges);
            if (!validationResult.isValid()) {
                return AttemptResult.invalid(content, validationResult.getErrors());
            }

            RecipeFlowGenerationResponseDTO generated = new RecipeFlowGenerationResponseDTO(steps, edges);
            return AttemptResult.valid(content, generated);
        } catch (JsonProcessingException ex) {
            return AttemptResult.invalid(content, List.of("Invalid JSON format: " + ex.getOriginalMessage()));
        }
    }

    private JsonNode parseJson(String content) throws JsonProcessingException {
        try {
            return objectMapper.readTree(content);
        } catch (JsonProcessingException firstEx) {
            String sanitized = stripCodeFences(content);
            return objectMapper.readTree(sanitized);
        }
    }

     private String stripCodeFences(String content) {
         String trimmed = content.trim();
         if (trimmed.startsWith("```")) {
             int firstNewLine = trimmed.indexOf('\n');
             int lastFence = trimmed.lastIndexOf("```");
             if (firstNewLine >= 0 && lastFence > firstNewLine) {
                 return trimmed.substring(firstNewLine + 1, lastFence).trim();
             }
         }
         return content;
     }

     private void normalizeExecutionDataTypes(
             List<RecipeExecutionStepDTO> steps,
             List<RecipeExecutionEdgeDTO> edges
     ) {
         for (RecipeExecutionStepDTO step : steps) {
             if (step == null) continue;
             step.setId(toStringValue(step.getId()));
             step.setAction(toStringValue(step.getAction()));
             step.setIngredientId(toStringValue(step.getIngredientId()));
             step.setQuantity(toStringValue(step.getQuantity()));
             step.setUnit(toStringValue(step.getUnit()));
             step.setStyle(toStringValue(step.getStyle()));
             step.setDuration(toStringValue(step.getDuration()));
             step.setFlame(toStringValue(step.getFlame()));
             step.setTemperature(toStringValue(step.getTemperature()));
             step.setNotes(toStringValue(step.getNotes()));
         }

         for (RecipeExecutionEdgeDTO edge : edges) {
             if (edge == null) continue;
             edge.setFrom(toStringValue(edge.getFrom()));
             edge.setTo(toStringValue(edge.getTo()));
         }
     }

    private String toStringValue(Object value) {
        if (value == null) return "";
        return String.valueOf(value);
    }

    private record AttemptResult(
            boolean valid,
            String rawContent,
            RecipeFlowGenerationResponseDTO response,
            List<String> errors
    ) {
        static AttemptResult valid(String rawContent, RecipeFlowGenerationResponseDTO response) {
            return new AttemptResult(true, rawContent, response, List.of());
        }

        static AttemptResult invalid(String rawContent, List<String> errors) {
            return new AttemptResult(false, rawContent, null, errors);
        }
    }
}
