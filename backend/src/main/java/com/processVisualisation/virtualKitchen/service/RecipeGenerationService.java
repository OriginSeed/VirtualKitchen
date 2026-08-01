package com.processVisualisation.virtualKitchen.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.client.AIClient;
import com.processVisualisation.virtualKitchen.ai.dto.AIRequest;
import com.processVisualisation.virtualKitchen.ai.dto.AIResponse;
import com.processVisualisation.virtualKitchen.dto.RecipeFlowGenerationResponseDTO;
import com.processVisualisation.virtualKitchen.exception.RecipeFlowGenerationException;
import com.processVisualisation.virtualKitchen.service.recipe.RecipeFlowPromptBuilder;
import com.processVisualisation.virtualKitchen.service.recipe.RecipeFlowValidationResult;
import com.processVisualisation.virtualKitchen.service.recipe.RecipeValidator;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
public class RecipeGenerationService {

    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAP_TYPE = new TypeReference<>() {
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
            JsonNode nodesNode = root.path("nodes");
            JsonNode edgesNode = root.path("edges");

            if (!nodesNode.isArray() || !edgesNode.isArray()) {
                return AttemptResult.invalid(content, List.of("Top-level JSON must contain arrays: nodes and edges"));
            }

             List<Map<String, Object>> nodes = objectMapper.convertValue(nodesNode, LIST_OF_MAP_TYPE);
             List<Map<String, Object>> edges = objectMapper.convertValue(edgesNode, LIST_OF_MAP_TYPE);

             // Normalize node data: ensure all step/condition/parallel object fields are strings
             normalizeNodeDataTypes(nodes);

             RecipeFlowValidationResult validationResult = recipeValidator.validate(nodes, edges);
            if (!validationResult.isValid()) {
                return AttemptResult.invalid(content, validationResult.getErrors());
            }

            RecipeFlowGenerationResponseDTO generated = new RecipeFlowGenerationResponseDTO(nodes, edges);
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

     private void normalizeNodeDataTypes(List<Map<String, Object>> nodes) {
         for (Map<String, Object> node : nodes) {
             Object dataObj = node.get("data");
             if (dataObj instanceof Map<?, ?>) {
                 @SuppressWarnings("unchecked")
                 Map<String, Object> data = (Map<String, Object>) dataObj;

                 // For recipeStepNode, ensure step object fields are all strings
                 if ("recipeStepNode".equals(node.get("type"))) {
                     Object stepObj = data.get("step");
                     if (stepObj instanceof Map<?, ?>) {
                         @SuppressWarnings("unchecked")
                         Map<String, Object> step = (Map<String, Object>) stepObj;
                         coerceObjectFieldsToStrings(step);
                     }
                 }

                 // For conditionNode, ensure condition object fields are all strings
                 if ("conditionNode".equals(node.get("type"))) {
                     Object conditionObj = data.get("condition");
                     if (conditionObj instanceof Map<?, ?>) {
                         @SuppressWarnings("unchecked")
                         Map<String, Object> condition = (Map<String, Object>) conditionObj;
                         coerceObjectFieldsToStrings(condition);
                     }
                 }

                 // For parallelStartNode/parallelEndNode, ensure parallel object fields are strings
                 if ("parallelStartNode".equals(node.get("type")) || "parallelEndNode".equals(node.get("type"))) {
                     Object parallelObj = data.get("parallel");
                     if (parallelObj instanceof Map<?, ?>) {
                         @SuppressWarnings("unchecked")
                         Map<String, Object> parallel = (Map<String, Object>) parallelObj;
                         coerceObjectFieldsToStrings(parallel);
                     }
                 }
             }
         }
     }

     private void coerceObjectFieldsToStrings(Map<String, Object> obj) {
         for (String key : new java.util.ArrayList<>(obj.keySet())) {
             Object value = obj.get(key);
             if (value == null) {
                 obj.put(key, "");
             } else if (value instanceof String) {
                 // Already a string
             } else if (value instanceof Number) {
                 // Convert number to string
                 obj.put(key, value.toString());
             } else if (value instanceof Boolean) {
                 // Convert boolean to string
                 obj.put(key, value.toString());
             } else {
                 // Fallback: convert to string representation
                 obj.put(key, value.toString());
             }
         }
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
