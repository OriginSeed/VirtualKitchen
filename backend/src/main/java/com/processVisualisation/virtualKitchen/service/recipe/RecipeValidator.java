package com.processVisualisation.virtualKitchen.service.recipe;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class RecipeValidator {

    private static final Set<String> SUPPORTED_NODE_TYPES = Set.of(
            "recipeStepNode",
            "conditionNode",
            "parallelStartNode",
            "parallelEndNode",
            "sectionNode"
    );

    public RecipeFlowValidationResult validate(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        List<String> errors = new ArrayList<>();

        if (nodes == null) {
            errors.add("nodes must be an array");
            return new RecipeFlowValidationResult(false, errors);
        }
        if (edges == null) {
            errors.add("edges must be an array");
            return new RecipeFlowValidationResult(false, errors);
        }

        Set<String> nodeIds = new LinkedHashSet<>();
        Set<String> edgeIds = new HashSet<>();

        for (int i = 0; i < nodes.size(); i++) {
            Map<String, Object> node = nodes.get(i);
            validateNode(node, i, nodeIds, errors);
        }

        for (int i = 0; i < edges.size(); i++) {
            Map<String, Object> edge = edges.get(i);
            validateEdge(edge, i, edgeIds, nodeIds, errors);
        }

        return new RecipeFlowValidationResult(errors.isEmpty(), errors);
    }

    private void validateNode(Map<String, Object> node, int index, Set<String> nodeIds, List<String> errors) {
        if (node == null) {
            errors.add("node[" + index + "] is null");
            return;
        }

        String id = asString(node.get("id"));
        String type = asString(node.get("type"));
        Object dataObj = node.get("data");

        if (isBlank(id)) {
            errors.add("node[" + index + "].id is required");
        } else if (!nodeIds.add(id)) {
            errors.add("node id must be unique: " + id);
        }

        if (isBlank(type)) {
            errors.add("node[" + index + "].type is required");
            return;
        }

        if (!SUPPORTED_NODE_TYPES.contains(type)) {
            errors.add("node[" + index + "] has unknown type: " + type);
            return;
        }

        if (!(dataObj instanceof Map<?, ?>)) {
            errors.add("node[" + index + "].data must be an object");
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) dataObj;

        if (isBlank(asString(data.get("title")))) {
            errors.add("node[" + index + "].data.title is required");
        }

        if ("recipeStepNode".equals(type) && !(data.get("step") instanceof Map<?, ?>)) {
            errors.add("node[" + index + "] recipeStepNode requires data.step object");
        }

        if ("conditionNode".equals(type) && !(data.get("condition") instanceof Map<?, ?>)) {
            errors.add("node[" + index + "] conditionNode requires data.condition object");
        }

        if ("parallelStartNode".equals(type)) {
            validateParallelKind(data, "start", index, errors);
        }

        if ("parallelEndNode".equals(type)) {
            validateParallelKind(data, "end", index, errors);
        }
    }

    private void validateParallelKind(Map<String, Object> data, String expectedKind, int index, List<String> errors) {
        if (!(data.get("parallel") instanceof Map<?, ?> parallelObj)) {
            errors.add("node[" + index + "] parallel node requires data.parallel object");
            return;
        }

        Object kind = parallelObj.get("kind");
        if (!expectedKind.equals(asString(kind))) {
            errors.add("node[" + index + "] parallel kind must be '" + expectedKind + "'");
        }
    }

    private void validateEdge(
            Map<String, Object> edge,
            int index,
            Set<String> edgeIds,
            Set<String> nodeIds,
            List<String> errors
    ) {
        if (edge == null) {
            errors.add("edge[" + index + "] is null");
            return;
        }

        String id = asString(edge.get("id"));
        String source = asString(edge.get("source"));
        String target = asString(edge.get("target"));

        if (isBlank(id)) {
            errors.add("edge[" + index + "].id is required");
        } else if (!edgeIds.add(id)) {
            errors.add("edge id must be unique: " + id);
        }

        if (isBlank(source)) {
            errors.add("edge[" + index + "].source is required");
        } else if (!nodeIds.contains(source)) {
            errors.add("edge[" + index + "].source references unknown node: " + source);
        }

        if (isBlank(target)) {
            errors.add("edge[" + index + "].target is required");
        } else if (!nodeIds.contains(target)) {
            errors.add("edge[" + index + "].target references unknown node: " + target);
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
