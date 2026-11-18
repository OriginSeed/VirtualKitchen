package com.processVisualisation.virtualKitchen.ai.recipe.generation;

import com.processVisualisation.virtualKitchen.ai.recipe.edit.RecipeProcessEditPromptBuilder;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.ActionDefinition;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.FieldRequirement;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.IngredientDefinition;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.UnitDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.FIELD_DURATION;
import static com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.FIELD_FLAME_LEVEL;
import static com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.FIELD_PREPARATION_STYLE;
import static com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.FIELD_QUANTITY;
import static com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.FIELD_REPEAT_INTERVAL;
import static com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.FIELD_TEMPERATURE;

/**
 * Builds the system and user prompts used to convert free-form recipe text
 * into a semantic Process structure (named preparation flows + the MAIN cooking sequence, each a
 * flat list of STEP/CONDITION nodes) whose shape is defined by {@link RecipeProcessOutputSchema}. No React Flow field (id, position,
 * dimensions, handles) is ever requested — the application generates those
 * when converting the result into the Process working snapshot.
 * <p>
 * The VOCABULARY section is rendered entirely from {@link RecipeStepVocabularyProvider}
 * (the shared stepCatalogs.data.json), including each action's allowed targets and fields.
 */
@Component
public class RecipeProcessGenerationPromptBuilder {

    /**
     * Logged with every generation so a change in output quality can be tied to a prompt change.
     * Bump it whenever the system/initial/retry prompt wording or structure changes.
     */
    public static final String PROMPT_VERSION = "7";

    /** Aliases shown per ingredient — enough to recognise regional names without bloating the prompt. */
    private static final int MAX_INGREDIENT_ALIASES = 3;

    /** Prompt-facing name of each catalog field key (the output JSON key the model writes). */
    private static final Map<String, String> FIELD_OUTPUT_NAMES = Map.of(
            FIELD_QUANTITY, "quantity",
            FIELD_PREPARATION_STYLE, "preparationStyle",
            FIELD_TEMPERATURE, "temperature",
            FIELD_FLAME_LEVEL, "flameLevel",
            FIELD_DURATION, "duration",
            FIELD_REPEAT_INTERVAL, "repeatInterval"
    );

    /**
     * How to fill in a single STEP's fields. Shared with {@link RecipeProcessEditPromptBuilder} (via
     * {@link #stepAuthoringRulesAndVocabulary()}) so a step the model adds while editing follows exactly the same rules
     * as one it generates.
     */
    private static final String STEP_AUTHORING_RULES = """
                CHOOSING THE ACTION
                - action must be an id from ACTIONS below. Use the most specific action that fits
                  (e.g. "saute", "simmer", "deep-fry" rather than "cook"; "mince" rather than "cut").
                - Use "cook" only when the recipe names no technique at all.
                - When no listed action fits, use "custom" and set "customActionName" to a short verb phrase
                  (e.g. "Spherify"). Omit customActionName otherwise.
                - Respect each action's "on" targets: never put ingredients on an action that only acts on
                  prepared components, never "use" a prepared component from an ingredient-only action, and never
                  give a single-ingredient action ("1I") more than one ingredient.

                INGREDIENTS OF A STEP
                - "ingredients" is a list; a step's action can apply to multiple ingredients at once
                  (e.g. Chop applied to onion, tomato, and chilli, each with its own style).
                - "name" is the ingredient as the recipe words it (e.g. "boneless chicken breast"). Always write it.
                - ingredientId: the catalog id from INGREDIENTS. When nothing listed is the same ingredient, use
                  "custom" (its "name" becomes the custom ingredient's name). Never pick a different ingredient
                  just because it is listed.
                - EACH ingredient entry has its OWN quantity/unit/preparationStyle. Never put quantity, unit, or
                  preparation style at the step level — only inside that ingredient's own entry.
                - quantity: the number the recipe states for that ingredient in this step. Omit it when the recipe
                  gives no amount (and for units that carry no number: to-taste, as-needed).
                - unit: the recipe's unit mapped to a unit id below (e.g. "2 cloves garlic" -> quantity 2, unit
                  "clove"; "salt to taste" -> no quantity, unit "to-taste"). When no unit is stated, use the
                  ingredient's default unit shown in parentheses. For a unit that isn't listed (e.g. "a knob"),
                  use unit "custom" with "customUnit".
                - preparationStyle: ONE style id, only when the action lists "preparationStyle" and the recipe states
                  or clearly implies it (e.g. "finely chopped" -> "finely-chopped"). It must come from that action's
                  preparation style sets. Never combine several styles in one value. For a style that isn't listed,
                  use "custom" with "customPreparationStyle". Never invent one the recipe doesn't state.
                - "uses" lists the "ref" of each prepared component (a preparation's output) this step works with.
                - A step can have ingredients, prepared components, step outputs, any combination of them, or none:
                  a step with no target acts on what is already in the pan (e.g. "simmer", "cover",
                  "transfer to a plate").

                STEP OUTPUTS (REUSING AN EARLIER STEP'S RESULT)
                - Rarely needed: consecutive steps already continue from one another. Use "fromSteps" only when a
                  step works with the result of an EARLIER, non-adjacent STEP of the SAME flow (e.g. browned chicken
                  set aside and returned later).
                - "fromSteps" lists the stepIds of those earlier STEPs. Example: {"stepId":"b1","action":"sear",...,
                  "expectedOutput":"browned chicken"} ... later {"action":"add","fromSteps":["b1"],...}.
                - Set "stepId" ONLY on a step that a later step lists in "fromSteps", as a short slug unique within
                  its own flow. Omit stepId on every other step.
                - A "fromSteps" value is always a stepId you set on that earlier step — never its expectedOutput text.
                - Only reference a step that comes before this one, is a STEP (never a CONDITION), is not this step
                  itself, and has a non-empty expectedOutput.
                - Step outputs count as prepared components, so only actions whose targets include S may use them.
                - Use "uses" (not fromSteps) for the output of a preparation.

                CORE STEP FIELDS
                - actionDescription: a short natural-language description of what this step does. Always include it.
                  Mark what the step uses in it as @[words](target), keeping the recipe's own words (see ANNOTATIONS).
                - expectedOutput: the expected result/state after this step. Include it whenever it can be
                  reasonably inferred from the recipe (e.g. "onions turn golden brown"); omit it only when the
                  recipe gives no basis for it. Do not invent specific, unstated outcomes.
                - title (optional): a short imperative title of at most 6 words (e.g. "Temper the spices"). Omit it
                  when the action and its ingredients already say it.

                STEP SUMMARY (summary, REQUIRED ON EVERY STEP)
                - summary is what a cooking guide would SAY aloud for this step: 1-3 plain sentences in the second
                  person ("Now add ...", "Keep stirring until ..."). It is read by text-to-speech, so no annotations,
                  no markdown, no lists, no abbreviations like "tbsp" or "min" (say "tablespoons", "minutes").
                - It continues the story: open from where the previous step left the food ("Once the onions are
                  golden, ..."), and say what to look for before moving on (the expectedOutput in natural words).
                - The first step of a preparation starts that component fresh; its last step names what is ready.
                - The first MAIN step that uses a prepared component names it and says it was made earlier
                  ("Bring back the marinated chicken you set aside ...").
                - Mention amounts only when they help the cook (e.g. "two cups of water"); never read out every
                  ingredient quantity. Never invent facts the recipe does not support.

                ANNOTATIONS (IN actionDescription)
                - @[onions](ingredient:<that ingredient's ingredientId; its "name" when custom>),
                  @[marinated chicken](prepared:<the "uses" ref>), @[browned chicken](step:<the "fromSteps" stepId>).
                - Only annotate what THIS step lists in "ingredients", "uses" or "fromSteps"; each at most once.
                  Leave ordinary words plain. Never annotate a CONDITION.
                - Amounts stay in the ingredient entry, never in the annotation.

                ADVANCED PROPERTIES (OPTIONAL, ACTION-SPECIFIC)
                - Only use temperatureValue/temperatureUnit, flameLevel, duration, repeatInterval when the chosen
                  action lists that field AND the recipe states it or it can be determined reliably. Otherwise omit
                  them. Never invent a temperature, heat level or duration.
                - temperatureValue is a number and temperatureUnit is "C" or "F" (both or neither). What the number
                  means is given by the action's temperature context (oven/oil/liquid/surface/ambient) — an oven
                  temperature is not a heat level.
                - flameLevel is the burner/appliance setting (e.g. "medium-high"), not a temperature.
                - duration and repeatInterval are written as "<number> <seconds|minutes|hours>" (e.g. "5 minutes").
                  For a range ("5-7 minutes") use the lower bound and keep the range in actionDescription. Convert
                  pressure-cooker whistles to an approximate duration and keep the whistle count in actionDescription.
                - repeatInterval is how often the action repeats during the step (e.g. "stir every 2 minutes" ->
                  action "stir", repeatInterval "2 minutes").

                """;

    private final RecipeStepVocabularyProvider vocabulary;
    private final RecipeProcessOutputSchema outputSchema;

    /**
     * Everything that is identical for every request (instructions, schema, rules, vocabulary). It is sent as
     * the system prompt and only changes when the ingredient catalog does, so providers with prefix caching
     * (Ollama's KV cache, Gemini/OpenAI implicit caching) only have to process it once — each request then
     * only adds the recipe itself. Built on first use and rebuilt when the ingredient catalog version changes.
     */
    private volatile Prompts prompts;

    private record Prompts(long catalogVersion, String vocabularyBlock, String systemPrompt) {}

    public RecipeProcessGenerationPromptBuilder(
            RecipeStepVocabularyProvider recipeStepVocabularyProvider, RecipeProcessOutputSchema outputSchema
    ) {
        this.vocabulary = recipeStepVocabularyProvider;
        this.outputSchema = outputSchema;
    }

    private Prompts prompts() {
        long version = vocabulary.ingredientCatalogVersion();
        Prompts current = prompts;
        if (current == null || current.catalogVersion() != version) {
            String vocabularyBlock = buildVocabularyBlock(vocabulary);
            String systemPrompt = """
                Convert recipe text into a semantic recipe process structure.

                Return ONLY valid JSON.
                Do not return markdown, explanations, comments, or code fences.
                Do not include node ids, edge ids, positions, width, height, handles, or any other
                React Flow or UI presentation field. Only semantic recipe/process data.

                """ + buildSchemaAndRulesBlock(outputSchema, vocabularyBlock);
            current = new Prompts(version, vocabularyBlock, systemPrompt);
            prompts = current;
        }
        return current;
    }

    public String buildSystemPrompt() {
        return prompts().systemPrompt();
    }

    /** Changes whenever the vocabulary (and so the system prompt) does — lets dependent prompt caches rebuild. */
    public long vocabularyVersion() {
        return vocabulary.ingredientCatalogVersion();
    }

    /** The STEP field rules followed by the VOCABULARY section — the part of the system prompt the edit flow reuses. */
    public String stepAuthoringRulesAndVocabulary() {
        return STEP_AUTHORING_RULES + prompts().vocabularyBlock();
    }

    public String buildInitialPrompt(String recipeText) {
        return "RECIPE:\n"
                + recipeText
                + "\n\nReturn the JSON process structure only.";
    }

    public String buildRetryPrompt(String recipeText, String previousOutput, List<String> validationErrors) {
        return "RECIPE:\n"
                + recipeText
                + "\n\nYOUR PREVIOUS OUTPUT:\n"
                + (previousOutput == null || previousOutput.isBlank() ? "(empty)" : previousOutput)
                + "\n\nVALIDATION ERRORS IN THAT OUTPUT:\n"
                + String.join("; ", validationErrors)
                + "\n\nFix only these errors and return the complete corrected JSON only.";
    }

    private String buildSchemaAndRulesBlock(RecipeProcessOutputSchema outputSchema, String vocabularyBlock) {
        return outputSchema.promptBlock() + """

                RECIPE STRUCTURE: PREPARATIONS + MAIN
                - A recipe is a list of independent PREPARATIONS followed by exactly one MAIN cooking sequence.
                  The cook sees every preparation together in one Preparation board, each as its own flow of steps.
                - A preparation is prep work done before or alongside cooking that produces one named component:
                  cutting, mincing, marinating, making a paste or sauce, soaking, par-cooking rice, etc.
                - "output" names that component the way a cook would say it: "Chopped Onions", "Marinated Chicken",
                  "Ginger-Garlic Paste", "Par-boiled Rice". It must be unique within the recipe.
                - A preparation's LAST step makes its output: give that step "expectedOutput" equal to the "output"
                  name (e.g. the last step of "Marinated Chicken" has "expectedOutput":"Marinated Chicken").
                - One preparation per prepared component. Several prep actions on the same component go in one
                  preparation (e.g. cut the chicken, then marinate it -> one "Marinated Chicken" preparation).
                - Preparations are flat and independent: a preparation never uses another preparation's output.
                  If one prepared component is made from another (a paste used in a marinade), put both in the
                  same preparation.
                - Do not create a preparation for an ingredient that goes into the pan as-is: oil, ghee, water,
                  salt, whole or ground spices, stock, canned goods. MAIN uses those directly as "ingredients".
                - Routine handling (washing produce, peeling where the skin isn't eaten, patting dry) is covered by
                  the recipe's default preparation notes: do not write steps for it unless the recipe asks for
                  something specific (e.g. "soak the rice for 30 minutes", "peel and devein the prawns").
                - Omit "preparations" when the recipe needs no prep work.
                - "ref" is a short, unique, lowercase snake_case slug (e.g. "marinated_chicken") that MAIN steps
                  write in "uses" to work with that output. It is not a database id.
                - MAIN is the one continuous cooking sequence (usually one pot or pan) in cooking order. Its steps
                  bring in prepared components with "uses":[ref] and direct ingredients with "ingredients".
                - Every preparation's output must be used by at least one MAIN step.
                - A MAIN step does not have to name what is already in the pan: "simmer for 20 minutes",
                  "cover", "transfer the chicken to a plate" simply continue from the step before.
                - A component used on its own is the target of its action: "season the chicken with salt"
                  -> action "season", "uses" the chicken, "ingredients" [salt].

                STEPS ARE ORDERED
                - Each "steps" array is already in execution order — the application connects
                  consecutive steps automatically. Do not include edges, connections, or ordering fields.
                - Create a separate step for each meaningful cooking action; split multiple actions in one
                  sentence into separate steps; do not split one atomic action into multiple steps.

                NODE TYPES
                - STEP: a concrete cooking action.
                - CONDITION: a decision/check/repetition (if, otherwise, until, unless, check, verify, repeat until).
                  Do not create a condition for an ordinary cooking instruction that isn't actually a check.

                CONDITION BRANCHES
                - The application wires every CONDITION the same way: "Yes" continues to the NEXT node in
                  "steps"; "No" goes back to the STEP immediately BEFORE the condition, which is repeated until
                  the check passes. You do not output branches — only phrase and place the condition so this is right.
                - Place a CONDITION immediately AFTER the STEP whose result it checks, never as the first node of
                  a preparation or MAIN, and never directly after another CONDITION.
                - Phrase "title" as a yes/no question whose "Yes" means the step is done and cooking moves on, and
                  whose "No" means that previous step must continue/repeat (e.g. after "Fry the onions":
                  "Are the onions golden brown?" — not "Are the onions still pale?").
                - Since "Yes" is always the hoped-for answer, omit expectedResult (it defaults to success).

                """
                + STEP_AUTHORING_RULES
                + vocabularyBlock
                + """

                GENERAL RULES
                - Output ids exactly as listed. Aliases in [brackets] exist only to help you read the recipe —
                  never output an alias, a label, or an id that is not listed.
                - A value the catalog lacks is fine: use "custom" with its name (customActionName,
                  customUnit, customPreparationStyle, or the ingredient's "name"). Never silently map it onto an
                  unrelated catalog entry.
                - Every ingredient amount the recipe gives appears exactly once, on the step where it is used.
                - Keep quantities, units, durations, temperatures, and cooking details faithful to the recipe text.
                - Do not invent ingredients, quantities, timings, or temperatures the recipe doesn't state.
                - Keep the recipe's meaning unchanged.
                - Return ONLY minified JSON matching the OUTPUT shape, with every empty/default field omitted.

                EXAMPLE (shortened)
                {"preparations":[{"ref":"marinated_chicken","output":"Marinated Chicken","steps":[{"action":"cut","ingredients":[{"ingredientId":"chicken","name":"boneless chicken","quantity":500,"unit":"g","preparationStyle":"bite-sized"}],"actionDescription":"Cut the @[chicken](ingredient:chicken) into bite-sized pieces"},{"action":"marinate","ingredients":[{"ingredientId":"yogurt","name":"yogurt","quantity":0.5,"unit":"cup"}],"actionDescription":"Coat the chicken in @[yogurt](ingredient:yogurt) and rest","expectedOutput":"Marinated Chicken","duration":"30 minutes"}]},{"ref":"chopped_onions","output":"Chopped Onions","steps":[{"action":"chop","ingredients":[{"ingredientId":"onion","name":"onions","quantity":2,"unit":"piece","preparationStyle":"chopped"}],"actionDescription":"Chop the @[onions](ingredient:onion)","expectedOutput":"Chopped Onions"}]}],"main":{"name":"Chicken Curry","steps":[{"action":"heat","ingredients":[{"ingredientId":"oil","name":"oil","quantity":3,"unit":"tbsp"}],"actionDescription":"Heat the @[oil](ingredient:oil) in a deep pan","flameLevel":"medium"},{"action":"saute","uses":["chopped_onions"],"actionDescription":"Fry the @[onions](prepared:chopped_onions) until golden","duration":"8 minutes"},{"nodeType":"CONDITION","title":"Are the onions golden?","actionDescription":"Check the colour of the onions"},{"action":"add","uses":["marinated_chicken"],"actionDescription":"Add the @[chicken](prepared:marinated_chicken) and its marinade"},{"action":"simmer","actionDescription":"Cover and simmer until the chicken is cooked","flameLevel":"low","duration":"20 minutes"}]}}
                (The ingredientIds above are illustrative: always use the ids listed in INGREDIENTS.)
                """;
    }

    // --- vocabulary rendering (on first use, and again whenever the ingredient catalog changes) ---

    private static String buildVocabularyBlock(RecipeStepVocabularyProvider vocabulary) {
        StringBuilder out = new StringBuilder("VOCABULARY\n\n");

        out.append("""
                ACTIONS — one per line: id | on | fields | meaning
                  on: I = ingredients, S = prepared components (preparation outputs via "uses", step outputs via
                      "fromSteps"), "-" = no target, 1I = at most one ingredient, a trailing "!" = names its target
                      when it starts on something new (it may continue on what is in the pan without one).
                  fields: the ONLY fields this action may use. "!" = required, "*" = strongly recommended when the
                      recipe states it. preparationStyle{...} lists the allowed preparation style sets;
                      temperature(...) gives what the temperature measures.
                """);
        Map<String, List<ActionDefinition>> actionsByCategory = groupBy(vocabulary.actions(), ActionDefinition::category);
        for (RecipeStepVocabularyProvider.CategoryDefinition category : vocabulary.actionCategories()) {
            List<ActionDefinition> inCategory = actionsByCategory.getOrDefault(category.id(), List.of());
            if (inCategory.isEmpty()) continue;
            out.append(category.label()).append(":\n");
            for (ActionDefinition action : inCategory) {
                out.append("- ").append(action.id())
                        .append(" | ").append(describeTargets(action))
                        .append(" | ").append(describeFields(action))
                        .append(" | ").append(action.description())
                        .append('\n');
            }
        }

        out.append("\nPREPARATION STYLE SETS (preparationStyle must be a style id from one of the action's sets, or \"custom\"):\n");
        vocabulary.preparationStyleSets().forEach(set ->
                out.append("- ").append(set.id()).append(": ").append(String.join(", ", set.styles())).append('\n'));

        out.append("\nINGREDIENTS — id=name (default unit) [aliases]; ingredientId is the id before \"=\":\n");
        Map<String, List<IngredientDefinition>> ingredientsByCategory = groupBy(vocabulary.ingredients(), IngredientDefinition::category);
        for (RecipeStepVocabularyProvider.CategoryDefinition category : vocabulary.ingredientCategories()) {
            List<IngredientDefinition> inCategory = ingredientsByCategory.getOrDefault(category.id(), List.of());
            if (inCategory.isEmpty()) continue;
            out.append(category.label()).append(": ")
                    .append(inCategory.stream().map(RecipeProcessGenerationPromptBuilder::describeIngredient).collect(Collectors.joining(", ")))
                    .append('\n');
        }

        out.append("\nUNITS:\n");
        Map<String, List<UnitDefinition>> unitsByCategory = groupBy(vocabulary.units(), UnitDefinition::category);
        for (RecipeStepVocabularyProvider.CategoryDefinition category : vocabulary.unitCategories()) {
            List<UnitDefinition> inCategory = unitsByCategory.getOrDefault(category.id(), List.of());
            if (inCategory.isEmpty()) continue;
            boolean nonNumeric = inCategory.stream().noneMatch(UnitDefinition::quantifiable);
            out.append("- ").append(category.label()).append(nonNumeric ? " (quantity must be null)" : "").append(": ")
                    .append(inCategory.stream().map(UnitDefinition::id).collect(Collectors.joining(", ")))
                    .append('\n');
        }

        out.append("\nflameLevel (heat level): ").append(String.join(", ", vocabulary.flameLevelIdSet())).append('\n');
        out.append("temperatureUnit: ").append(String.join(", ", vocabulary.temperatureUnitIds())).append('\n');
        out.append("temperature contexts: ").append(vocabulary.temperatureContextLabels().entrySet().stream()
                .map(entry -> entry.getKey() + " = " + entry.getValue())
                .collect(Collectors.joining("; "))).append('\n');
        out.append("duration / repeatInterval units: ").append(String.join(", ", vocabulary.durationUnitIds())).append('\n');
        return out.toString();
    }

    private static String describeTargets(ActionDefinition action) {
        String targets = switch (action.actionOn()) {
            case INGREDIENT -> action.multipleIngredients() ? "I" : "1I";
            case PROCESS -> "S";
            case BOTH -> (action.multipleIngredients() ? "I" : "1I") + "+S";
            case NONE -> "-";
        };
        return action.actionOnRequired() ? targets + "!" : targets;
    }

    private static String describeFields(ActionDefinition action) {
        List<String> parts = new ArrayList<>();
        action.fields().forEach((key, requirement) -> {
            String name = FIELD_OUTPUT_NAMES.get(key);
            if (name == null) return; // unitId travels with quantity
            StringBuilder part = new StringBuilder(name);
            if (FIELD_PREPARATION_STYLE.equals(key)) {
                part.append('{').append(String.join(",", action.preparationStyleSets())).append('}');
            }
            if (FIELD_TEMPERATURE.equals(key) && action.temperatureContext() != null) {
                part.append('(').append(action.temperatureContext()).append(')');
            }
            if (requirement == FieldRequirement.REQUIRED) part.append('!');
            if (requirement == FieldRequirement.RECOMMENDED) part.append('*');
            parts.add(part.toString());
        });
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    private static String describeIngredient(IngredientDefinition ingredient) {
        StringBuilder entry = new StringBuilder(ingredient.id()).append('=').append(ingredient.name())
                .append(" (").append(ingredient.defaultUnit()).append(')');
        if (!ingredient.aliases().isEmpty()) {
            entry.append(" [")
                    .append(String.join(", ", ingredient.aliases().subList(0, Math.min(MAX_INGREDIENT_ALIASES, ingredient.aliases().size()))))
                    .append(']');
        }
        return entry.toString();
    }

    private static <T> Map<String, List<T>> groupBy(List<T> entries, java.util.function.Function<T, String> key) {
        Map<String, List<T>> grouped = new LinkedHashMap<>();
        entries.forEach(entry -> grouped.computeIfAbsent(key.apply(entry), k -> new ArrayList<>()).add(entry));
        return grouped;
    }
}
