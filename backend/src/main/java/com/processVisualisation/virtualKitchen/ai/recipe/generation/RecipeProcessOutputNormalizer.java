package com.processVisualisation.virtualKitchen.ai.recipe.generation;

import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedActionOnDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedActionOnIngredientDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedRecipeProcessDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedRecipeStepDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.ActionDefinition;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.FieldRequirement;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.IngredientDefinition;
import com.processVisualisation.virtualKitchen.recipe.model.StepAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.CUSTOM_ID;
import static com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.FIELD_DURATION;
import static com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.FIELD_FLAME_LEVEL;
import static com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.FIELD_PREPARATION_STYLE;
import static com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.FIELD_QUANTITY;
import static com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.FIELD_REPEAT_INTERVAL;
import static com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.FIELD_TEMPERATURE;

/**
 * Maps the compact model output ({@link RecipeProcessOutput}) onto the {@code Generated*DTO}s the validator and the
 * frontend work with, filling every default the model was told to omit: nodeType STEP, expectedOutput "",
 * expectedResult success, empty target lists, and a stepId for steps that no later step references.
 * <p>
 * When a vocabulary is available it also repairs, deterministically, what the model got only cosmetically wrong, so a
 * recipe that is understandable never fails on catalog detail. Every repair is reported in
 * {@link Normalized#repairs()}:
 * <ul>
 *   <li>an action, ingredient, unit or preparation style written as a name or alias becomes its catalog id;</li>
 *   <li>one the catalog doesn't have is kept as a named custom value — never mapped onto an unrelated entry;</li>
 *   <li>a compound preparation style ("skinless,boneless") keeps the first style the action allows, the rest
 *       becoming an ingredient note; a style on an action that takes none becomes a note as well;</li>
 *   <li>an optional step field the action doesn't declare (flame level, temperature, duration, repeat interval), or
 *       one that can't be read, is dropped; a duration range keeps its lower bound;</li>
 *   <li>a quantity on a non-numeric unit is dropped, a quantity without a unit gets the ingredient's default unit,
 *       and a required amount the recipe doesn't give becomes "as-needed";</li>
 *   <li>a {@code fromSteps} entry naming an earlier step by its expectedOutput ("browned chicken") instead of its
 *       stepId points at that step;</li>
 *   <li>a step whose output a later step uses gets its actionDescription as expectedOutput when it has none;</li>
 *   <li>a preparation's last STEP — the step that makes its output — gets the preparation's {@code output} name as its
 *       expectedOutput (a different text it had moves into its description), since the editor names a preparation
 *       flow by that step.</li>
 * </ul>
 * Structural problems (unknown references, misplaced conditions, missing descriptions, actions on the wrong kind of
 * target) are left untouched for {@link RecipeProcessGenerationValidator} to report.
 */
@Component
public class RecipeProcessOutputNormalizer {

    private static final Pattern STYLE_SEPARATOR = Pattern.compile("\\s*(?:,|/|;|&|\\band\\b|\\+)\\s*");
    private static final Pattern NUMERIC_ID = Pattern.compile("^\\d+$");
    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)(?:\\s*(?:-|–|to)\\s*\\d+(?:\\.\\d+)?)?\\s*([a-zA-Z]+)");
    private static final Pattern CANONICAL_DURATION = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*([a-zA-Z]+)\\s*$");
    private static final String AS_NEEDED_UNIT = "as-needed";
    private static final String DEFAULT_COUNT_UNIT = "piece";

    /** The normalized MAIN process and preparations (as subprocesses), plus every repair that was applied. */
    public record Normalized(GeneratedRecipeProcessDTO main, List<GeneratedRecipeProcessDTO> preparations, List<String> repairs) {}

    private final RecipeStepVocabularyProvider vocabulary;

    /** Carries every value over unchanged (no vocabulary to repair against). */
    public RecipeProcessOutputNormalizer() {
        this(null);
    }

    @Autowired
    public RecipeProcessOutputNormalizer(RecipeStepVocabularyProvider vocabulary) {
        this.vocabulary = vocabulary;
    }

    public Normalized normalize(RecipeProcessOutput output) {
        List<String> repairs = new ArrayList<>();
        if (output == null) return new Normalized(null, List.of(), repairs);

        GeneratedRecipeProcessDTO main = output.main() == null ? null
                : toProcess(null, output.main().name(), output.main().steps(), "main", repairs);

        List<GeneratedRecipeProcessDTO> preparations = new ArrayList<>();
        if (output.preparations() != null) {
            for (RecipeProcessOutput.Preparation preparation : output.preparations()) {
                if (preparation == null) {
                    preparations.add(null);
                    continue;
                }
                String label = "preparation '" + (isBlank(preparation.output()) ? preparation.ref() : preparation.output()) + "'";
                GeneratedRecipeProcessDTO flow = toProcess(preparation.ref(), preparation.output(), preparation.steps(), label, repairs);
                nameFlowOutput(flow, label, repairs);
                preparations.add(flow);
            }
        }
        return new Normalized(main, preparations, repairs);
    }

    private GeneratedRecipeProcessDTO toProcess(String ref, String name, List<RecipeProcessOutput.Node> nodes, String label, List<String> repairs) {
        List<GeneratedRecipeStepDTO> steps = null;
        if (nodes != null) {
            Set<String> takenStepIds = new HashSet<>();
            nodes.stream().filter(Objects::nonNull).map(RecipeProcessOutput.Node::stepId)
                    .filter(Objects::nonNull).forEach(takenStepIds::add);
            steps = new ArrayList<>();
            for (int i = 0; i < nodes.size(); i++) {
                RecipeProcessOutput.Node node = nodes.get(i);
                String stepLabel = label + " step " + (i + 1);
                if (node == null) {
                    steps.add(null);
                } else if ("CONDITION".equals(node.nodeType())) {
                    // A generated CONDITION never needs an id: nothing can reference its output.
                    steps.add(toStep(node, null, stepLabel, repairs));
                } else {
                    String stepId = node.stepId() != null ? node.stepId() : generateStepId(i, takenStepIds);
                    steps.add(toStep(node, stepId, stepLabel, repairs));
                }
            }
            resolveStepReferencesByOutput(steps, label, repairs);
            fitStepAnnotations(steps, label, repairs);
            fillReferencedOutputs(steps, label, repairs);
        }
        return new GeneratedRecipeProcessDTO(ref, name, steps);
    }

    /** Maps a single node, using {@code stepId} (may be null) as its id — STEP or CONDITION alike. Repairs are applied but not reported. */
    public GeneratedRecipeStepDTO toStep(RecipeProcessOutput.Node node, String stepId) {
        return toStep(node, stepId, "step", new ArrayList<>());
    }

    private GeneratedRecipeStepDTO toStep(RecipeProcessOutput.Node node, String stepId, String label, List<String> repairs) {
        if (node == null) return null;
        GeneratedRecipeStepDTO step = new GeneratedRecipeStepDTO();
        step.setNodeType(node.nodeType() == null || node.nodeType().isBlank() ? "STEP" : node.nodeType());
        step.setActionDescription(node.actionDescription());
        step.setExpectedOutput(node.expectedOutput() == null ? "" : node.expectedOutput());

        if ("CONDITION".equals(step.getNodeType())) {
            // A check is read, not linked: any annotation in its text becomes plain words.
            if (StepAnnotations.hasAnnotations(step.getActionDescription())) {
                step.setActionDescription(StepAnnotations.toPlainText(step.getActionDescription()));
                repairs.add(label + ": a check's text has no annotations — kept as plain text");
            }
            step.setStepId(stepId);
            step.setTitle(node.title());
            step.setExpectedResult(node.expectedResult() == null || node.expectedResult().isBlank() ? "success" : node.expectedResult());
            return step;
        }

        step.setStepId(stepId);
        step.setAction(node.action());
        step.setCustomActionName(node.customActionName());
        normalizeAction(step, label, repairs);
        ActionDefinition action = vocabulary == null ? null : vocabulary.action(step.getAction()).orElse(null);

        List<GeneratedActionOnIngredientDTO> ingredients = new ArrayList<>();
        if (node.ingredients() != null) {
            for (RecipeProcessOutput.Ingredient ingredient : node.ingredients()) {
                ingredients.add(toIngredient(ingredient, action, label, repairs));
            }
        }
        step.setActionOn(new GeneratedActionOnDTO(
                ingredients,
                node.uses() == null ? List.of() : node.uses(),
                node.fromSteps() == null ? List.of() : node.fromSteps()
        ));
        step.setTemperatureValue(node.temperatureValue());
        step.setTemperatureUnit(node.temperatureUnit());
        step.setFlameLevel(node.flameLevel());
        step.setDuration(node.duration());
        step.setRepeatInterval(node.repeatInterval());
        step.setTitle(node.title() == null || node.title().isBlank() ? null : node.title().trim());
        step.setSummary(plainSummary(node.summary(), label, repairs));
        step.setSourceAliases(node.sourceStepIds());
        if (action != null) normalizeStepFields(step, action, label, repairs);
        // Step annotations are fitted once the flow's step references are resolved (see fitStepAnnotations).
        step.setActionDescription(normalizeAnnotations(step.getActionDescription(), step, node.ingredients(), false, label, repairs));
        return step;
    }

    /** A step summary is spoken, so any annotation in it becomes its plain words; blank means none. */
    private static String plainSummary(String summary, String label, List<String> repairs) {
        if (summary == null || summary.isBlank()) return null;
        if (StepAnnotations.hasAnnotations(summary)) {
            repairs.add(label + ": a summary has no annotations — kept as plain text");
            return StepAnnotations.toPlainText(summary).trim();
        }
        return summary.trim();
    }

    /**
     * The description of an edited step with its annotations fitted to the step (see the private overload). Used by
     * the edit flow, whose step fields arrive already normalized.
     */
    public String normalizeAnnotations(String description, GeneratedRecipeStepDTO step) {
        return normalizeAnnotations(description, step, null, true, "step", new ArrayList<>());
    }

    /**
     * Fits a step's @ annotations to the step itself, so each points at one of its own targets the way the editor
     * stores them: an ingredient annotation written with a catalog id, an alias or the ingredient's name becomes that
     * Action On entry's id (or {@code custom:<name>}); a {@code prepared:} or {@code step:} annotation must name one of
     * the step's "uses" refs or "fromSteps" ids. Anything else is turned back into its plain words — a repair, never
     * a failure (annotations are optional).
     */
    private String normalizeAnnotations(String description, GeneratedRecipeStepDTO step, List<RecipeProcessOutput.Ingredient> raw,
                                        boolean checkSteps, String label, List<String> repairs) {
        if (!StepAnnotations.hasAnnotations(description) || step.getActionOn() == null) return description;
        Map<String, String> ingredientIds = new HashMap<>();
        List<GeneratedActionOnIngredientDTO> ingredients = step.getActionOn().getIngredients() == null ? List.of() : step.getActionOn().getIngredients();
        for (int i = 0; i < ingredients.size(); i++) {
            GeneratedActionOnIngredientDTO ingredient = ingredients.get(i);
            if (ingredient == null) continue;
            String id = StepAnnotations.ingredientId(ingredient.getIngredientId(), ingredient.getCustomIngredientName());
            if (id.isEmpty()) continue;
            List<String> keys = new ArrayList<>(List.of(id, String.valueOf(ingredient.getIngredientId())));
            if (ingredient.getCustomIngredientName() != null) keys.add(ingredient.getCustomIngredientName());
            RecipeProcessOutput.Ingredient source = raw != null && i < raw.size() ? raw.get(i) : null;
            if (source != null) {
                if (source.ingredientId() != null && !CUSTOM_ID.equals(source.ingredientId())) keys.add(source.ingredientId());
                if (source.name() != null) keys.add(source.name());
                if (source.customIngredientName() != null) keys.add(source.customIngredientName());
            }
            keys.forEach(key -> ingredientIds.putIfAbsent(annotationKey(key), id));
        }
        List<String> uses = step.getActionOn().getProcesses() == null ? List.of() : step.getActionOn().getProcesses();
        List<String> fromSteps = step.getActionOn().getSteps() == null ? List.of() : step.getActionOn().getSteps();

        return StepAnnotations.remapIds(description, annotation -> {
            String id = switch (annotation.kind()) {
                case StepAnnotations.INGREDIENT -> {
                    String written = annotation.id().startsWith(StepAnnotations.CUSTOM_INGREDIENT_PREFIX)
                            ? annotation.id().substring(StepAnnotations.CUSTOM_INGREDIENT_PREFIX.length()) : annotation.id();
                    String found = ingredientIds.get(annotationKey(annotation.id()));
                    if (found == null) found = ingredientIds.get(annotationKey(written));
                    if (found == null && vocabulary != null) found = ingredientIds.get(annotationKey(resolveIngredientId(written)));
                    yield found;
                }
                case StepAnnotations.PREPARED -> uses.contains(annotation.id()) ? annotation.id() : null;
                default -> !checkSteps || fromSteps.contains(annotation.id()) ? annotation.id() : null;
            };
            if (id == null) {
                repairs.add(label + ": the annotation \"" + annotation.label() + "\" names something the step doesn't list — kept as plain text");
            }
            return id;
        });
    }

    private static String annotationKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** One ingredient of an edit operation: catalog/alias/custom resolution only (no step context for action rules). */
    public GeneratedActionOnIngredientDTO toIngredient(RecipeProcessOutput.Ingredient ingredient) {
        return toIngredient(ingredient, null, "ingredient", new ArrayList<>());
    }

    private GeneratedActionOnIngredientDTO toIngredient(
            RecipeProcessOutput.Ingredient source, ActionDefinition action, String label, List<String> repairs
    ) {
        if (source == null) return null;
        GeneratedActionOnIngredientDTO ingredient = new GeneratedActionOnIngredientDTO(
                source.ingredientId(), source.quantity(), source.unit(), source.preparationStyle(),
                source.customIngredientName(), source.customUnit(), source.customPreparationStyle(), null);
        if (vocabulary == null) return ingredient;

        normalizeIngredientId(ingredient, source.name(), label, repairs);
        String ingredientLabel = label + " " + describe(ingredient);
        normalizeUnit(ingredient, ingredientLabel, repairs);
        if (action != null) {
            normalizePreparationStyle(ingredient, action, ingredientLabel, repairs);
            normalizeQuantity(ingredient, action, ingredientLabel, repairs);
        } else if (!isBlank(ingredient.getPreparationStyle()) && !CUSTOM_ID.equals(ingredient.getPreparationStyle())
                && !vocabulary.isPreparationStyleId(ingredient.getPreparationStyle())) {
            vocabulary.resolvePreparationStyleId(ingredient.getPreparationStyle()).ifPresent(ingredient::setPreparationStyle);
        }
        return ingredient;
    }

    /** A known id as-is; otherwise the id its name/alias resolves to; otherwise unchanged (the validator reports it). */
    public String resolveIngredientId(String ingredientId) {
        if (vocabulary == null || ingredientId == null || vocabulary.ingredient(ingredientId).isPresent()) {
            return ingredientId;
        }
        return vocabulary.resolveIngredientId(ingredientId).orElse(ingredientId);
    }

    // --- repairs ---

    private void normalizeAction(GeneratedRecipeStepDTO step, String label, List<String> repairs) {
        String action = step.getAction();
        if (vocabulary == null || isBlank(action) || vocabulary.action(action).isPresent()) return;
        Optional<String> canonical = vocabulary.resolveActionId(action);
        if (canonical.isPresent()) {
            step.setAction(canonical.get());
            repairs.add(label + ": action '" + action + "' mapped to '" + canonical.get() + "'");
            return;
        }
        step.setAction(CUSTOM_ID);
        if (isBlank(step.getCustomActionName())) step.setCustomActionName(humanize(action));
        repairs.add(label + ": action '" + action + "' is not in the catalog, kept as custom action '" + step.getCustomActionName() + "'");
    }

    private void normalizeIngredientId(GeneratedActionOnIngredientDTO ingredient, String name, String label, List<String> repairs) {
        String id = ingredient.getIngredientId();
        if (CUSTOM_ID.equals(id)) {
            if (isBlank(ingredient.getCustomIngredientName()) && !isBlank(name)) ingredient.setCustomIngredientName(name.trim());
            return;
        }
        if (!isBlank(id) && vocabulary.ingredient(id).isPresent()) return;

        Optional<String> resolved = isBlank(id) ? Optional.empty() : vocabulary.resolveIngredientId(id);
        if (resolved.isEmpty() && !isBlank(name)) resolved = vocabulary.resolveIngredientId(name);
        if (resolved.isPresent()) {
            ingredient.setIngredientId(resolved.get());
            repairs.add(label + ": ingredient '" + (isBlank(id) ? name : id) + "' mapped to catalog id '" + resolved.get() + "'");
            return;
        }

        String customName = !isBlank(name) ? name.trim()
                : !isBlank(id) && !NUMERIC_ID.matcher(id.trim()).matches() ? humanize(id) : null;
        if (customName == null) return; // an unknown numeric id with no name: nothing to keep — the validator reports it
        ingredient.setIngredientId(CUSTOM_ID);
        if (isBlank(ingredient.getCustomIngredientName())) ingredient.setCustomIngredientName(customName);
        repairs.add(label + ": ingredient '" + customName + "' is not in the catalog, kept as a custom ingredient");
    }

    private void normalizeUnit(GeneratedActionOnIngredientDTO ingredient, String label, List<String> repairs) {
        String unit = ingredient.getUnit();
        if (isBlank(unit) || vocabulary.unit(unit).isPresent()) return;
        Optional<String> canonical = vocabulary.resolveUnitId(unit);
        if (canonical.isPresent()) {
            ingredient.setUnit(canonical.get());
            if (!unit.equals(unit.toUpperCase(Locale.ROOT))) { // legacy UnitType values (COUNT, GRAM, ...) are not worth reporting
                repairs.add(label + ": unit '" + unit + "' mapped to '" + canonical.get() + "'");
            }
            return;
        }
        ingredient.setUnit(CUSTOM_ID);
        if (isBlank(ingredient.getCustomUnit())) ingredient.setCustomUnit(unit.trim());
        repairs.add(label + ": unit '" + unit + "' is not in the catalog, kept as a custom unit");
    }

    private void normalizePreparationStyle(GeneratedActionOnIngredientDTO ingredient, ActionDefinition action, String label, List<String> repairs) {
        String style = ingredient.getPreparationStyle();
        if (isBlank(style)) return;
        boolean takesStyle = action.hasField(FIELD_PREPARATION_STYLE);

        if (CUSTOM_ID.equals(style)) {
            if (!takesStyle) {
                String text = ingredient.getCustomPreparationStyle();
                ingredient.setPreparationStyle(null);
                ingredient.setCustomPreparationStyle(null);
                if (!isBlank(text)) {
                    appendNote(ingredient, text);
                    repairs.add(label + ": action " + action.id() + " takes no preparation style, kept '" + text + "' as an ingredient note");
                }
            }
            return;
        }

        String knownId = isKnownIngredient(ingredient.getIngredientId()) ? ingredient.getIngredientId() : null;
        Set<String> allowed = takesStyle ? vocabulary.allowedPreparationStyles(action.id(), knownId) : Set.of();
        if (allowed.contains(style)) return;

        List<String> parts = Arrays.stream(STYLE_SEPARATOR.split(style.trim())).filter(part -> !part.isBlank()).toList();
        if (!takesStyle) {
            ingredient.setPreparationStyle(null);
            String note = humanizeAll(parts);
            appendNote(ingredient, note);
            repairs.add(label + ": action " + action.id() + " takes no preparation style, kept '" + note + "' as an ingredient note");
            return;
        }

        String chosen = null;
        int chosenIndex = -1;
        for (int i = 0; i < parts.size(); i++) {
            String id = styleId(parts.get(i));
            if (id != null && allowed.contains(id)) {
                chosen = id;
                chosenIndex = i;
                break;
            }
        }
        if (chosen != null) {
            ingredient.setPreparationStyle(chosen);
            List<String> rest = new ArrayList<>(parts);
            rest.remove(chosenIndex);
            if (!rest.isEmpty()) appendNote(ingredient, humanizeAll(rest));
            repairs.add(label + ": preparation style '" + style + "' kept as '" + chosen + "'"
                    + (rest.isEmpty() ? "" : " with '" + humanizeAll(rest) + "' as an ingredient note"));
            return;
        }

        String text = humanizeAll(parts);
        ingredient.setPreparationStyle(CUSTOM_ID);
        if (isBlank(ingredient.getCustomPreparationStyle())) ingredient.setCustomPreparationStyle(text);
        repairs.add(label + ": preparation style '" + style + "' is not one action " + action.id() + " offers, kept as custom style '" + text + "'");
    }

    private void normalizeQuantity(GeneratedActionOnIngredientDTO ingredient, ActionDefinition action, String label, List<String> repairs) {
        String unit = ingredient.getUnit();
        boolean quantifiable = isBlank(unit) || vocabulary.isQuantifiableUnit(unit);
        Double quantity = ingredient.getQuantity();

        if (quantity != null && !quantifiable) {
            ingredient.setQuantity(null);
            repairs.add(label + ": quantity dropped because unit '" + unit + "' carries no amount");
        } else if (quantity != null && isBlank(unit)) {
            String defaultUnit = vocabulary.ingredient(ingredient.getIngredientId())
                    .map(IngredientDefinition::defaultUnit).filter(u -> !isBlank(u)).orElse(DEFAULT_COUNT_UNIT);
            ingredient.setUnit(defaultUnit);
            repairs.add(label + ": no unit given, used the default unit '" + defaultUnit + "'");
        } else if (quantity == null && quantifiable && action.fieldRequirement(FIELD_QUANTITY) == FieldRequirement.REQUIRED) {
            ingredient.setUnit(AS_NEEDED_UNIT);
            ingredient.setCustomUnit(null);
            repairs.add(label + ": the recipe gives no amount, marked as needed");
        }
    }

    private void normalizeStepFields(GeneratedRecipeStepDTO step, ActionDefinition action, String label, List<String> repairs) {
        String flameLevel = step.getFlameLevel();
        if (!isBlank(flameLevel)) {
            if (!action.hasField(FIELD_FLAME_LEVEL)) {
                step.setFlameLevel(null);
                repairs.add(label + ": action " + action.id() + " has no heat level, dropped '" + flameLevel + "'");
            } else if (!vocabulary.isFlameLevelId(flameLevel)) {
                String id = flameLevel.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
                step.setFlameLevel(vocabulary.isFlameLevelId(id) ? id : null);
                if (step.getFlameLevel() == null) repairs.add(label + ": unknown heat level '" + flameLevel + "' dropped");
            }
        }

        boolean hasTemperature = step.getTemperatureValue() != null || !isBlank(step.getTemperatureUnit()) || !isBlank(step.getTemperature());
        if (hasTemperature) {
            if (!action.hasField(FIELD_TEMPERATURE)) {
                clearTemperature(step);
                repairs.add(label + ": action " + action.id() + " has no temperature, dropped it");
            } else if (step.getTemperatureValue() != null || !isBlank(step.getTemperatureUnit())) {
                String unit = step.getTemperatureUnit() == null ? null : step.getTemperatureUnit().trim().toUpperCase(Locale.ROOT).replace("°", "");
                if (step.getTemperatureValue() != null && unit != null && vocabulary.temperatureUnitIds().contains(unit)) {
                    step.setTemperatureUnit(unit);
                } else {
                    clearTemperature(step);
                    repairs.add(label + ": temperature without a usable value and unit (C/F) dropped");
                }
            }
        }

        step.setDuration(normalizeDuration(step.getDuration(), FIELD_DURATION, "duration", action, label, repairs));
        step.setRepeatInterval(normalizeDuration(step.getRepeatInterval(), FIELD_REPEAT_INTERVAL, "repeat interval", action, label, repairs));
    }

    private String normalizeDuration(String value, String fieldKey, String fieldName, ActionDefinition action, String label, List<String> repairs) {
        if (isBlank(value)) return value;
        if (!action.hasField(fieldKey)) {
            repairs.add(label + ": action " + action.id() + " has no " + fieldName + ", dropped '" + value + "'");
            return null;
        }
        Matcher canonical = CANONICAL_DURATION.matcher(value);
        if (canonical.matches() && vocabulary.resolveDurationUnit(canonical.group(2)).isPresent()) return value;

        Matcher matcher = DURATION.matcher(value);
        while (matcher.find()) {
            Optional<String> unit = vocabulary.resolveDurationUnit(matcher.group(2));
            if (unit.isPresent()) {
                String normalized = matcher.group(1) + " " + unit.get();
                repairs.add(label + ": " + fieldName + " '" + value + "' read as '" + normalized + "'");
                return normalized;
            }
        }
        repairs.add(label + ": " + fieldName + " '" + value + "' could not be read, dropped");
        return null;
    }

    /**
     * A preparation flow is named by its last STEP's expectedOutput: it must equal the preparation's {@code output}.
     * A different text it had is kept, appended to its description.
     */
    private void nameFlowOutput(GeneratedRecipeProcessDTO flow, String label, List<String> repairs) {
        String output = flow.getName();
        if (isBlank(output) || flow.getSteps() == null) return;
        GeneratedRecipeStepDTO last = null;
        for (GeneratedRecipeStepDTO step : flow.getSteps()) {
            if (step != null && "STEP".equals(step.getNodeType())) last = step;
        }
        if (last == null || output.trim().equals(last.getExpectedOutput() == null ? null : last.getExpectedOutput().trim())) return;
        String previous = last.getExpectedOutput();
        if (!isBlank(previous)) {
            String description = last.getActionDescription();
            last.setActionDescription(isBlank(description) ? previous.trim() : description.trim() + " (" + previous.trim() + ")");
        }
        last.setExpectedOutput(output.trim());
        repairs.add(label + ": its last step now names the output '" + output.trim() + "'");
    }

    /**
     * Models often write the output they mean ("browned chicken", "browned_chicken") in {@code fromSteps} instead of the
     * stepId they forgot to give that step. An entry that is no stepId of this process but matches an earlier STEP's
     * expectedOutput (ignoring case, spaces, '_' and '-') points at that step — the nearest one when several match.
     * Anything else is left for the validator.
     */
    private void resolveStepReferencesByOutput(List<GeneratedRecipeStepDTO> steps, String label, List<String> repairs) {
        Set<String> stepIds = new HashSet<>();
        steps.stream().filter(Objects::nonNull).map(GeneratedRecipeStepDTO::getStepId).filter(Objects::nonNull).forEach(stepIds::add);
        for (int i = 0; i < steps.size(); i++) {
            GeneratedRecipeStepDTO step = steps.get(i);
            if (step == null || step.getActionOn() == null || step.getActionOn().getSteps().isEmpty()) continue;
            List<String> resolved = new ArrayList<>();
            boolean changed = false;
            for (String ref : step.getActionOn().getSteps()) {
                String target = ref == null || stepIds.contains(ref) ? null : earlierStepWithOutput(steps, i, ref);
                if (target != null) {
                    repairs.add(label + " step " + (i + 1) + ": fromSteps '" + ref + "' is an expected output, not a stepId — linked to the step that makes it");
                    changed = true;
                }
                resolved.add(target != null ? target : ref);
            }
            if (changed) step.getActionOn().setSteps(resolved);
        }
    }

    private static String earlierStepWithOutput(List<GeneratedRecipeStepDTO> steps, int consumerIndex, String text) {
        String wanted = comparable(text);
        if (wanted.isEmpty()) return null;
        for (int i = consumerIndex - 1; i >= 0; i--) {
            GeneratedRecipeStepDTO candidate = steps.get(i);
            if (candidate != null && "STEP".equals(candidate.getNodeType()) && candidate.getStepId() != null
                    && wanted.equals(comparable(candidate.getExpectedOutput()))) {
                return candidate.getStepId();
            }
        }
        return null;
    }

    private static String comparable(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", " ");
    }

    /**
     * A {@code step:} annotation must name one of the step's "fromSteps": written as that step's stepId, or as its
     * expected output (like a fromSteps entry, see {@link #resolveStepReferencesByOutput}). Others become plain words.
     */
    private void fitStepAnnotations(List<GeneratedRecipeStepDTO> steps, String label, List<String> repairs) {
        for (int i = 0; i < steps.size(); i++) {
            GeneratedRecipeStepDTO step = steps.get(i);
            if (step == null || step.getActionOn() == null || !StepAnnotations.hasAnnotations(step.getActionDescription())) continue;
            List<String> fromSteps = step.getActionOn().getSteps() == null ? List.of() : step.getActionOn().getSteps();
            int index = i;
            step.setActionDescription(StepAnnotations.remapIds(step.getActionDescription(), annotation -> {
                if (!StepAnnotations.STEP.equals(annotation.kind()) || fromSteps.contains(annotation.id())) return annotation.id();
                String byOutput = earlierStepWithOutput(steps, index, annotation.id());
                if (byOutput != null && fromSteps.contains(byOutput)) return byOutput;
                repairs.add(label + " step " + (index + 1) + ": the annotation \"" + annotation.label()
                        + "\" names a step this step doesn't use — kept as plain text");
                return null;
            }));
        }
    }

    /** A step whose output a later step uses must name that output; its own description stands in when it has none. */
    private void fillReferencedOutputs(List<GeneratedRecipeStepDTO> steps, String label, List<String> repairs) {
        Map<String, GeneratedRecipeStepDTO> byId = new HashMap<>();
        for (GeneratedRecipeStepDTO step : steps) {
            if (step == null) continue;
            if (step.getActionOn() != null) {
                for (String ref : step.getActionOn().getSteps()) {
                    GeneratedRecipeStepDTO source = ref == null ? null : byId.get(ref);
                    if (source != null && "STEP".equals(source.getNodeType())
                            && isBlank(source.getExpectedOutput()) && !isBlank(source.getActionDescription())) {
                        source.setExpectedOutput(StepAnnotations.toPlainText(source.getActionDescription()));
                        repairs.add(label + ": step " + ref + " is used by a later step, so its description became its expected output");
                    }
                }
            }
            if (step.getStepId() != null) byId.putIfAbsent(step.getStepId(), step);
        }
    }

    // --- helpers ---

    private boolean isKnownIngredient(String ingredientId) {
        return ingredientId != null && !CUSTOM_ID.equals(ingredientId) && vocabulary.ingredient(ingredientId).isPresent();
    }

    private String styleId(String text) {
        if (vocabulary.isPreparationStyleId(text)) return text;
        return vocabulary.resolvePreparationStyleId(text)
                .or(() -> vocabulary.resolvePreparationStyleId(text.replace('-', ' ')))
                .orElse(null);
    }

    private String describe(GeneratedActionOnIngredientDTO ingredient) {
        if (CUSTOM_ID.equals(ingredient.getIngredientId())) return "'" + ingredient.getCustomIngredientName() + "'";
        return "'" + vocabulary.ingredientLabel(ingredient.getIngredientId()) + "'";
    }

    private static void clearTemperature(GeneratedRecipeStepDTO step) {
        step.setTemperatureValue(null);
        step.setTemperatureUnit(null);
        step.setTemperature(null);
    }

    private static void appendNote(GeneratedActionOnIngredientDTO ingredient, String note) {
        if (isBlank(note)) return;
        ingredient.setNotes(isBlank(ingredient.getNotes()) ? note : ingredient.getNotes() + "; " + note);
    }

    private static String humanizeAll(List<String> parts) {
        return String.join(", ", parts.stream().map(part -> part.trim().replace('-', ' ').replace('_', ' ')).toList());
    }

    /** "kashmiri_chilli-powder" -> "Kashmiri chilli powder". */
    private static String humanize(String value) {
        String text = value.trim().replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ");
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** "s<position>", suffixed until it collides with no stepId the model wrote itself. */
    private static String generateStepId(int index, Set<String> takenStepIds) {
        String candidate = "s" + (index + 1);
        while (!takenStepIds.add(candidate)) {
            candidate = candidate + "_";
        }
        return candidate;
    }
}
