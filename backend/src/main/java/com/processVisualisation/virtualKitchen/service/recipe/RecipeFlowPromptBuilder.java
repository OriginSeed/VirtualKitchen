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
                + "- spread positions of nodes uniformly\n\n"
                + "Supported node types:\n"
                + "1) recipeStepNode\n"
                + "- required data: title (string), step (object)\n"
                + "- step object contains structured fields (ALL FIELDS MUST BE STRINGS or empty strings):\n"
                + "  * action, ingredientId, customIngredientName: strings (use empty string if not applicable)\n"
                + "  * quantity: string with units (e.g. \"2 cups\", not just \"2\")\n"
                + "  * specificationOption, customSpecification, unit, unitOption, customUnit, specification: strings\n"
                + "  * flame: one of [\"None\", \"Low\", \"Medium\", \"High\"] (default \"None\")\n"
                + "  * temperature, durationValue, durationUnit, duration: ALWAYS strings (e.g. durationValue=\"20\", durationUnit=\"min\", duration=\"20 min\")\n"
                + "  * repeatAction, repeatEveryValue, repeatEveryUnit, repeatInterval, notes: strings\n"
                + "- optional data: icon (string), description (string), duration (string), sectionId (string|null), stepNumber (number)\n"
                + "- default dimensions: width 320, height 190\n"
                + "- default style example: {\"width\":320,\"height\":190}\n\n"
                + "2) conditionNode\n"
                + "- required data: title (string), condition (object with question, expectedResult, successLabel, failureLabel, notes - ALL STRINGS)\n"
                + "- optional data: yesLabel (string), noLabel (string), description (string), sectionId (string|null)\n"
                + "- default dimensions: width 190, height 190\n"
                + "- default style example: {\"width\":190,\"height\":190}\n\n"
                + "3) parallelStartNode\n"
                + "- required data: title (string), parallel (object with kind=\"start\" - STRING)\n"
                + "- optional data: description (string), sectionId (string|null)\n"
                + "- default dimensions: width 180, height 92\n"
                + "- default style example: {\"width\":180,\"height\":92}\n\n"
                + "4) parallelEndNode\n"
                + "- required data: title (string), parallel (object with kind=\"end\" - STRING)\n"
                + "- optional data: description (string), sectionId (string|null)\n"
                + "- default dimensions: width 180, height 92\n"
                + "- default style example: {\"width\":180,\"height\":92}\n\n"
                + "Edge object schema:\n"
                + "- required: id (string), source (string), target (string) - ALL STRINGS\n"
                + "- optional: sourceHandle (string|null), targetHandle (string|null), type (string), animated (boolean), style (object), data (object), label (string|null)\n"
                + "- suggested type: \"smoothstep\"\n\n"
                + "CRITICAL TYPE RULES:\n"
                + "- ALL structured field values in step/condition/parallel objects MUST be strings\n"
                + "- Never output numbers for quantity, duration fields - always wrap in quotes\n"
                + "- quantity must include unit descriptors (\"2 cups\", \"1 tablespoon\", etc.)\n"
                + "- Empty/missing fields MUST be empty string \"\", never null or omitted\n"
                + "- ID fields (id, source, target) MUST be strings\n\n"
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
                + "{\"nodes\":[{\"id\":\"n1\",\"type\":\"recipeStepNode\",\"data\":{\"title\":\"Wash rice\",\"step\":{\"action\":\"\",\"ingredientId\":\"\",\"customIngredientName\":\"rice\",\"quantity\":\"2 cups\",\"specificationOption\":\"\",\"customSpecification\":\"\",\"unit\":\"\",\"unitOption\":\"\",\"customUnit\":\"\",\"specification\":\"\",\"flame\":\"None\",\"temperature\":\"\",\"durationValue\":\"\",\"durationUnit\":\"\",\"duration\":\"\",\"repeatAction\":\"\",\"repeatEveryValue\":\"\",\"repeatEveryUnit\":\"\",\"repeatInterval\":\"\",\"notes\":\"Wash twice\"}}},{\"id\":\"n2\",\"type\":\"recipeStepNode\",\"data\":{\"title\":\"Boil rice\",\"step\":{\"action\":\"\",\"ingredientId\":\"\",\"customIngredientName\":\"rice\",\"quantity\":\"\",\"specificationOption\":\"\",\"customSpecification\":\"\",\"unit\":\"\",\"unitOption\":\"\",\"customUnit\":\"\",\"specification\":\"\",\"flame\":\"None\",\"temperature\":\"\",\"durationValue\":\"20\",\"durationUnit\":\"min\",\"duration\":\"20 min\",\"repeatAction\":\"\",\"repeatEveryValue\":\"\",\"repeatEveryUnit\":\"\",\"repeatInterval\":\"\",\"notes\":\"Use 4 cups water\"}}}],\"edges\":[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\",\"type\":\"smoothstep\"}]}"
                + "\n\nREMEMBER: quantity=\"2 cups\" NOT quantity=2. All numeric values in step objects must be quoted strings.";
    }
}
