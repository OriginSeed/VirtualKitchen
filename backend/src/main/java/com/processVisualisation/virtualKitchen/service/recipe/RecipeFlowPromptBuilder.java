package com.processVisualisation.virtualKitchen.service.recipe;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RecipeFlowPromptBuilder {

    private static final String ACTION_IDS = "add|remove|pour|season|cut|chop|slice|dice|heat|boil|fry|bake|stir|mix|whisk|wait|rest|serve|garnish";
    private static final String INGREDIENT_IDS = "water|oil|salt|sugar|rice|onion|tomato|garlic|ginger|chili|potato|carrot|capsicum|egg|milk|butter|chicken|custom";
    private static final String UNIT_IDS = "ml|l|cup|g|kg|piece|tsp|tbsp|pinch|custom";
    private static final String PREPARATION_STYLE_IDS = "fine|medium|large|thin-slice|thick-slice|julienne|rough-chop|custom";
    private static final String FLAME_LEVEL_IDS = "low|medium|high|custom";

    public String buildSystemPrompt() {
        return "Generate strict JSON only for a recipe execution model. "
                + "No markdown, prose, code fences, or UI graph fields.";
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
                + "Validation errors that must be fixed:\n"
                + errors + "\n\n"
                + "Return corrected JSON only.";
    }

    private String buildSchemaAndRulesBlock() {
        return "Return JSON with exactly two arrays: steps, edges.\n"
                + "Step schema (all string fields): id, action, ingredientId, quantity, unit, style, duration, flame, temperature, notes.\n"
                + "Edge schema: from, to.\n"
                + "Use these controlled vocabularies:\n"
                + "- action in [" + ACTION_IDS + "]\n"
                + "- ingredientId in [" + INGREDIENT_IDS + "] when possible, else custom\n"
                + "- unit in [" + UNIT_IDS + "]\n"
                + "- style in [" + PREPARATION_STYLE_IDS + "]\n"
                + "- flame in [" + FLAME_LEVEL_IDS + "]\n"
                + "Rules:\n"
                + "- unique step ids\n"
                + "- edges reference existing step ids\n"
                + "- keep steps semantically complete for cooking execution\n"
                + "- no UI data (position,width,height,style,icons,handles,type,data,source,target,id on edges)\n"
                + "- unknown optional values must be empty string\n"
                + "Example:\n"
                + "{\"steps\":[{\"id\":\"s1\",\"action\":\"add\",\"ingredientId\":\"rice\",\"quantity\":\"2\",\"unit\":\"cup\",\"style\":\"\",\"duration\":\"\",\"flame\":\"\",\"temperature\":\"\",\"notes\":\"\"}],\"edges\":[]}";
    }
}
