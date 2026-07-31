package com.processVisualisation.virtualKitchen.service.recipe;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RecipeFlowPromptBuilder {

    public String buildSystemPrompt() {
        return "You generate JSON graph payloads for a recipe flow editor. Return strict JSON only. "
                + "No markdown. No code fences. No prose. "
                + "Do not include position coordinates or layout calculations.";
    }

    public String buildInitialPrompt(String recipeText) {
        return buildSchemaAndRulesBlock() + "\n"
                + "Input recipe text:\n"
                + recipeText + "\n\n"
                + "Now generate a valid JSON object only.";
    }

    public String buildRetryPrompt(String recipeText, String previousOutput, List<String> validationErrors) {
        String errors = String.join("; ", validationErrors);
        return buildSchemaAndRulesBlock() + "\n"
                + "Input recipe text:\n"
                + recipeText + "\n\n"
                + "Previous invalid output:\n"
                + previousOutput + "\n\n"
                + "Validation errors that must be fixed:\n"
                + errors + "\n\n"
                + "Return corrected JSON only.";
    }

    private String buildSchemaAndRulesBlock() {
        return "Application flow graph contract:\n"
                + "Top-level JSON object fields:\n"
                + "- nodes: array of node objects\n"
                + "- edges: array of edge objects\n\n"
                + "Node object schema:\n"
                + "- required: id (string), type (string), data (object)\n"
                + "- optional: draggable (boolean), selectable (boolean), deletable (boolean), style (object), parentId (string), extent (string), measured (object)\n"
                + "- do NOT include position\n\n"
                + "Supported node types:\n"
                + "1) recipeStepNode\n"
                + "- required data: title (string), step (object)\n"
                + "- optional data: icon (string), description (string), duration (string), sectionId (string|null), stepNumber (number)\n"
                + "- default dimensions: width 320, height 190\n"
                + "- default style example: {\"width\":320,\"height\":190}\n\n"
                + "2) conditionNode\n"
                + "- required data: title (string), condition (object)\n"
                + "- optional data: yesLabel (string), noLabel (string), description (string), sectionId (string|null)\n"
                + "- default dimensions: width 190, height 190\n"
                + "- default style example: {\"width\":190,\"height\":190}\n\n"
                + "3) parallelStartNode\n"
                + "- required data: title (string), parallel (object with kind=\"start\")\n"
                + "- optional data: description (string), sectionId (string|null)\n"
                + "- default dimensions: width 180, height 92\n"
                + "- default style example: {\"width\":180,\"height\":92}\n\n"
                + "4) parallelEndNode\n"
                + "- required data: title (string), parallel (object with kind=\"end\")\n"
                + "- optional data: description (string), sectionId (string|null)\n"
                + "- default dimensions: width 180, height 92\n"
                + "- default style example: {\"width\":180,\"height\":92}\n\n"
                + "5) sectionNode\n"
                + "- required data: title (string)\n"
                + "- optional data: description (string)\n"
                + "- default dimensions: width 720, height 360\n"
                + "- default style example: {\"width\":720,\"height\":360}\n\n"
                + "Edge object schema:\n"
                + "- required: id (string), source (string), target (string)\n"
                + "- optional: sourceHandle (string|null), targetHandle (string|null), type (string), animated (boolean), style (object), data (object), label (string|null)\n"
                + "- suggested type: \"smoothstep\"\n\n"
                + "Graph rules:\n"
                + "- node IDs must be unique\n"
                + "- edge IDs must be unique\n"
                + "- every edge.source and edge.target must reference existing node IDs\n"
                + "- use only supported node types\n"
                + "- produce logical sequence and branching only\n"
                + "- do not output layout, coordinates, markdown, or explanation\n\n"
                + "JSON schema shape:\n"
                + "{\"nodes\":[{\"id\":\"string\",\"type\":\"recipeStepNode|conditionNode|parallelStartNode|parallelEndNode|sectionNode\",\"data\":{}}],\"edges\":[{\"id\":\"string\",\"source\":\"nodeId\",\"target\":\"nodeId\"}]}\n\n"
                + "Example input:\n"
                + "Take two cups of rice. Wash twice. Add four cups of water. Cook for twenty minutes.\n\n"
                + "Example output:\n"
                + "{\"nodes\":[{\"id\":\"n1\",\"type\":\"recipeStepNode\",\"data\":{\"title\":\"Wash rice\",\"step\":{\"action\":\"\",\"ingredientId\":\"\",\"customIngredientName\":\"rice\",\"quantity\":\"2 cups\",\"specificationOption\":\"\",\"customSpecification\":\"\",\"unit\":\"\",\"unitOption\":\"\",\"customUnit\":\"\",\"specification\":\"\",\"flame\":\"None\",\"temperature\":\"\",\"durationValue\":\"\",\"durationUnit\":\"\",\"duration\":\"\",\"repeatAction\":\"\",\"repeatEveryValue\":\"\",\"repeatEveryUnit\":\"\",\"repeatInterval\":\"\",\"notes\":\"Wash twice\"}}},{\"id\":\"n2\",\"type\":\"recipeStepNode\",\"data\":{\"title\":\"Boil rice\",\"step\":{\"action\":\"\",\"ingredientId\":\"\",\"customIngredientName\":\"rice\",\"quantity\":\"\",\"specificationOption\":\"\",\"customSpecification\":\"\",\"unit\":\"\",\"unitOption\":\"\",\"customUnit\":\"\",\"specification\":\"\",\"flame\":\"None\",\"temperature\":\"\",\"durationValue\":\"20\",\"durationUnit\":\"min\",\"duration\":\"20 min\",\"repeatAction\":\"\",\"repeatEveryValue\":\"\",\"repeatEveryUnit\":\"\",\"repeatInterval\":\"\",\"notes\":\"Use 4 cups water\"}}}],\"edges\":[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\",\"type\":\"smoothstep\"}]}";
    }
}
