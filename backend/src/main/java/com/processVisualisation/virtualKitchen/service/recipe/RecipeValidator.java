package com.processVisualisation.virtualKitchen.service.recipe;

import com.processVisualisation.virtualKitchen.dto.RecipeExecutionEdgeDTO;
import com.processVisualisation.virtualKitchen.dto.RecipeExecutionStepDTO;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class RecipeValidator {

    public RecipeFlowValidationResult validate(List<RecipeExecutionStepDTO> steps, List<RecipeExecutionEdgeDTO> edges) {
        List<String> errors = new ArrayList<>();

        if (steps == null) {
            errors.add("steps must be an array");
            return new RecipeFlowValidationResult(false, errors);
        }
        if (edges == null) {
            errors.add("edges must be an array");
            return new RecipeFlowValidationResult(false, errors);
        }

        Set<String> stepIds = new LinkedHashSet<>();
        Set<String> edgeKeys = new HashSet<>();

        for (int i = 0; i < steps.size(); i++) {
            RecipeExecutionStepDTO step = steps.get(i);
            validateStep(step, i, stepIds, errors);
        }

        for (int i = 0; i < edges.size(); i++) {
            RecipeExecutionEdgeDTO edge = edges.get(i);
            validateEdge(edge, i, edgeKeys, stepIds, errors);
        }

        return new RecipeFlowValidationResult(errors.isEmpty(), errors);
    }

    private void validateStep(RecipeExecutionStepDTO step, int index, Set<String> stepIds, List<String> errors) {
        if (step == null) {
            errors.add("step[" + index + "] is null");
            return;
        }

        String id = asString(step.getId());
        String action = asString(step.getAction());

        if (isBlank(id)) {
            errors.add("step[" + index + "].id is required");
        } else if (!stepIds.add(id)) {
            errors.add("step id must be unique: " + id);
        }

        if (isBlank(action)) {
            errors.add("step[" + index + "].action is required");
        }
    }

    private void validateEdge(
            RecipeExecutionEdgeDTO edge,
            int index,
            Set<String> edgeKeys,
            Set<String> stepIds,
            List<String> errors
    ) {
        if (edge == null) {
            errors.add("edge[" + index + "] is null");
            return;
        }

        String from = asString(edge.getFrom());
        String to = asString(edge.getTo());

        if (isBlank(from)) {
            errors.add("edge[" + index + "].from is required");
        } else if (!stepIds.contains(from)) {
            errors.add("edge[" + index + "].from references unknown step: " + from);
        }

        if (isBlank(to)) {
            errors.add("edge[" + index + "].to is required");
        } else if (!stepIds.contains(to)) {
            errors.add("edge[" + index + "].to references unknown step: " + to);
        }

        if (!isBlank(from) && !isBlank(to)) {
            String key = from + "->" + to;
            if (!edgeKeys.add(key)) {
                errors.add("duplicate edge from " + from + " to " + to);
            }
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
