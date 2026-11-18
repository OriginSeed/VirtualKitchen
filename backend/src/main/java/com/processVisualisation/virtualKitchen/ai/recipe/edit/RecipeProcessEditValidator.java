package com.processVisualisation.virtualKitchen.ai.recipe.edit;

import com.processVisualisation.virtualKitchen.ai.recipe.edit.dto.ProcessEditOperationDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessGenerationValidator;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutput;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessOutputNormalizer;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedActionOnDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedActionOnIngredientDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedRecipeStepDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider;
import com.processVisualisation.virtualKitchen.recipe.model.StepAnnotations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.processVisualisation.virtualKitchen.ai.recipe.edit.RecipeProcessEditOutputSchema.CLEARABLE_FIELDS;
import static com.processVisualisation.virtualKitchen.ai.recipe.edit.RecipeProcessEditOutputSchema.MAX_OPERATIONS;
import static com.processVisualisation.virtualKitchen.ai.recipe.vocabulary.RecipeStepVocabularyProvider.CUSTOM_ID;
import static com.processVisualisation.virtualKitchen.ai.recipe.edit.dto.ProcessEditOperationDTO.NEW_FLOW;
import static com.processVisualisation.virtualKitchen.ai.recipe.edit.dto.ProcessEditOperationDTO.START;

/**
 * Validates the model's edit operations against the process they target and, when they are valid, translates them
 * into {@link ProcessEditOperationDTO}s keyed by real node ids.
 * <p>
 * The operations are applied in order to a copy of the process's ordered node list, the same way the frontend
 * applies them to the graph. Each operation is checked as it is applied (it must reference a node that exists at
 * that point). The resulting list is then run through {@link RecipeProcessGenerationValidator#validateSteps}, and
 * only errors the original process did not already have are reported, so a hand-made step with, say, an unlisted
 * flame level never blocks an unrelated edit.
 */
@Component
public class RecipeProcessEditValidator {

    /** Prefix of the temporary references handed to the frontend for nodes the edit adds. */
    static final String NEW_REF_PREFIX = "new-";

    private final RecipeProcessGenerationValidator stepValidator;
    private final RecipeProcessOutputNormalizer normalizer;

    public RecipeProcessEditValidator(RecipeProcessGenerationValidator stepValidator, RecipeProcessOutputNormalizer normalizer) {
        this.stepValidator = stepValidator;
        this.normalizer = normalizer;
    }

    public record Result(List<String> errors, List<ProcessEditOperationDTO> operations) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    Result validate(RecipeProcessEditOutput output, RecipeProcessEditContext context) {
        List<String> errors = new ArrayList<>();
        if (output == null) {
            return new Result(List.of("the response is empty"), List.of());
        }
        if (isBlank(output.summary())) {
            errors.add("summary is required");
        }
        if (!isBlank(output.clarification())) {
            // A question back to the user: nothing is applied, so the operations don't matter.
            return new Result(errors, List.of());
        }

        List<RecipeProcessEditOutput.Operation> operations = output.operations() == null ? List.of() : output.operations();
        if (operations.isEmpty()) {
            errors.add("operations is empty: return at least one operation, or a clarification question");
            return new Result(errors, List.of());
        }
        if (operations.size() > MAX_OPERATIONS) {
            errors.add("too many operations (" + operations.size() + " > " + MAX_OPERATIONS + "): make only the changes the instruction asks for");
            return new Result(errors, List.of());
        }

        Simulation simulation = new Simulation(context);
        for (int i = 0; i < operations.size(); i++) {
            simulation.apply(operations.get(i), "operations[" + i + "]", errors);
        }
        if (!errors.isEmpty()) return new Result(errors, List.of());

        if (simulation.steps.isEmpty()) {
            errors.add("the operations would remove every node of the process");
            return new Result(errors, List.of());
        }

        Set<String> processAliases = context.processNameByAlias().keySet();
        Set<String> existingErrors = new HashSet<>(stepValidator.validateSteps(context.steps(), processAliases));
        for (String error : stepValidator.validateSteps(simulation.steps, processAliases)) {
            if (!existingErrors.contains(error)) errors.add(error);
        }
        return new Result(errors, errors.isEmpty() ? simulation.translated : List.of());
    }

    /** The process as an ordered alias-keyed list, mutated operation by operation. */
    private final class Simulation {
        private final RecipeProcessEditContext context;
        private final List<GeneratedRecipeStepDTO> steps = new ArrayList<>();
        /** Every alias a later operation may reference -> what the frontend knows it as (node id or "new-k"). */
        private final Map<String, String> refByAlias = new HashMap<>();
        private final Set<String> deleted = new HashSet<>();
        private final List<ProcessEditOperationDTO> translated = new ArrayList<>();
        private int added;

        Simulation(RecipeProcessEditContext context) {
            this.context = context;
            for (GeneratedRecipeStepDTO step : context.steps()) {
                steps.add(RecipeProcessEditContext.copy(step));
                refByAlias.put(step.getStepId(), context.nodeId(step.getStepId()));
            }
        }

        void apply(RecipeProcessEditOutput.Operation operation, String label, List<String> errors) {
            if (operation == null || isBlank(operation.op())) {
                errors.add(label + ".op is required");
                return;
            }
            switch (operation.op()) {
                case "ADD_STEP" -> addStep(operation, label, errors);
                case "ADD_CONDITION" -> addCondition(operation, label, errors);
                case "UPDATE_STEP" -> updateStep(operation, label, errors);
                case "UPDATE_CONDITION" -> updateCondition(operation, label, errors);
                case "ADD_INGREDIENT" -> addIngredient(operation, label, errors);
                case "UPDATE_INGREDIENT" -> updateIngredient(operation, label, errors);
                case "REMOVE_INGREDIENT" -> removeIngredient(operation, label, errors);
                case "REPLACE_INGREDIENT" -> replaceIngredient(operation, label, errors);
                case "DELETE_NODE" -> deleteNode(operation, label, errors);
                case "MOVE_NODE" -> moveNode(operation, label, errors);
                default -> errors.add(label + ".op is unknown: " + operation.op());
            }
        }

        private void addStep(RecipeProcessEditOutput.Operation operation, String label, List<String> errors) {
            Integer insertAt = insertionIndex(operation.after(), label + ".after", errors);
            if (operation.step() == null) {
                errors.add(label + ".step is required");
                return;
            }
            if (operation.step().nodeType() != null && !"STEP".equals(operation.step().nodeType())) {
                errors.add(label + ".step must be a STEP; use ADD_CONDITION to add a check");
                return;
            }
            String alias = newAlias(operation.step().stepId(), label + ".step.stepId", errors);
            if (insertAt == null || alias == null) return;

            GeneratedRecipeStepDTO step = RecipeProcessEditContext.copy(normalizer.toStep(operation.step(), alias));
            step.setNodeType("STEP");
            steps.add(insertAt, step);
            translated.add(ProcessEditOperationDTO.builder()
                    .op("ADD_STEP").after(ref(operation.after())).ref(refByAlias.get(alias)).step(toFrontend(step)).build());
        }

        private void addCondition(RecipeProcessEditOutput.Operation operation, String label, List<String> errors) {
            if (NEW_FLOW.equals(operation.after())) {
                errors.add(label + ".after NEW_FLOW starts a flow with a STEP: use ADD_STEP, then add the check after it");
                return;
            }
            Integer insertAt = insertionIndex(operation.after(), label + ".after", errors);
            String alias = newAlias(operation.stepId(), label + ".stepId", errors);
            if (insertAt == null || alias == null) return;

            RecipeProcessOutput.Node node = new RecipeProcessOutput.Node("CONDITION", alias, null, null, null, null, null,
                    operation.actionDescription(), null, null, null, null, null, null, operation.title(), operation.expectedResult(), null);
            GeneratedRecipeStepDTO condition = RecipeProcessEditContext.copy(normalizer.toStep(node, alias));
            steps.add(insertAt, condition);
            translated.add(ProcessEditOperationDTO.builder()
                    .op("ADD_CONDITION").after(ref(operation.after())).ref(refByAlias.get(alias)).step(toFrontend(condition)).build());
        }

        private void updateStep(RecipeProcessEditOutput.Operation operation, String label, List<String> errors) {
            GeneratedRecipeStepDTO step = existing(operation.target(), label + ".target", "STEP", errors);
            if (step == null) return;
            RecipeProcessEditOutput.StepPatch set = operation.set();
            List<String> clear = operation.clear() == null ? List.of() : operation.clear();
            if (set == null && clear.isEmpty()) {
                errors.add(label + " changes nothing: give \"set\" and/or \"clear\"");
                return;
            }
            for (String field : clear) {
                if (!CLEARABLE_FIELDS.contains(field)) errors.add(label + ".clear has an unknown field: " + field);
            }

            GeneratedRecipeStepDTO patch = new GeneratedRecipeStepDTO();
            if (set != null) {
                if (set.action() != null) { step.setAction(set.action()); patch.setAction(set.action()); }
                if (set.customActionName() != null) { step.setCustomActionName(set.customActionName()); patch.setCustomActionName(set.customActionName()); }
                if (set.expectedOutput() != null) { step.setExpectedOutput(set.expectedOutput()); patch.setExpectedOutput(set.expectedOutput()); }
                if (set.title() != null) { step.setTitle(set.title()); patch.setTitle(set.title()); }
                if (set.summary() != null) {
                    String summary = StepAnnotations.toPlainText(set.summary()).trim();
                    step.setSummary(summary);
                    patch.setSummary(summary);
                }
                if (set.temperatureValue() != null) { step.setTemperatureValue(set.temperatureValue()); patch.setTemperatureValue(set.temperatureValue()); }
                if (set.temperatureUnit() != null) { step.setTemperatureUnit(set.temperatureUnit()); patch.setTemperatureUnit(set.temperatureUnit()); }
                if (set.flameLevel() != null) { step.setFlameLevel(set.flameLevel()); patch.setFlameLevel(set.flameLevel()); }
                if (set.duration() != null) { step.setDuration(set.duration()); patch.setDuration(set.duration()); }
                if (set.repeatInterval() != null) { step.setRepeatInterval(set.repeatInterval()); patch.setRepeatInterval(set.repeatInterval()); }
                if (set.fromSteps() != null || set.uses() != null) {
                    patch.setActionOn(new GeneratedActionOnDTO(null, null, null));
                }
                if (set.fromSteps() != null) {
                    step.getActionOn().setSteps(new ArrayList<>(set.fromSteps()));
                    patch.getActionOn().setSteps(nodeRefs(set.fromSteps()));
                }
                if (set.uses() != null) {
                    step.getActionOn().setProcesses(new ArrayList<>(set.uses()));
                    patch.getActionOn().setProcesses(processRefs(set.uses()));
                }
                // After the targets above, so the description's annotations are fitted to the step as it now is.
                if (set.actionDescription() != null) {
                    String description = normalizer.normalizeAnnotations(set.actionDescription(), step);
                    step.setActionDescription(description);
                    patch.setActionDescription(frontendDescription(description));
                }
            }
            for (String field : clear) {
                switch (field) {
                    case "customActionName" -> step.setCustomActionName(null);
                    case "expectedOutput" -> step.setExpectedOutput("");
                    case "temperature" -> { step.setTemperatureValue(null); step.setTemperatureUnit(null); step.setTemperature(null); }
                    case "flameLevel" -> step.setFlameLevel(null);
                    case "duration" -> step.setDuration(null);
                    case "repeatInterval" -> step.setRepeatInterval(null);
                    case "fromSteps" -> step.getActionOn().setSteps(new ArrayList<>());
                    case "uses", "processes" -> step.getActionOn().setProcesses(new ArrayList<>());
                    default -> { }
                }
            }
            translated.add(ProcessEditOperationDTO.builder()
                    .op("UPDATE_STEP").target(ref(operation.target())).step(patch)
                    .clear(clear.isEmpty() ? null : List.copyOf(new LinkedHashSet<>(clear.stream().map(RecipeProcessEditValidator::frontendFieldName).toList()))).build());
        }

        private void updateCondition(RecipeProcessEditOutput.Operation operation, String label, List<String> errors) {
            GeneratedRecipeStepDTO condition = existing(operation.target(), label + ".target", "CONDITION", errors);
            if (condition == null) return;
            if (operation.title() == null && operation.actionDescription() == null && operation.expectedResult() == null) {
                errors.add(label + " changes nothing: give title, actionDescription and/or expectedResult");
                return;
            }
            GeneratedRecipeStepDTO patch = new GeneratedRecipeStepDTO();
            if (operation.title() != null) { condition.setTitle(operation.title()); patch.setTitle(operation.title()); }
            if (operation.actionDescription() != null) {
                // A check's text is read, not linked.
                String description = StepAnnotations.toPlainText(operation.actionDescription());
                condition.setActionDescription(description);
                patch.setActionDescription(description);
            }
            if (operation.expectedResult() != null) { condition.setExpectedResult(operation.expectedResult()); patch.setExpectedResult(operation.expectedResult()); }
            translated.add(ProcessEditOperationDTO.builder().op("UPDATE_CONDITION").target(ref(operation.target())).step(patch).build());
        }

        private void addIngredient(RecipeProcessEditOutput.Operation operation, String label, List<String> errors) {
            GeneratedRecipeStepDTO step = existing(operation.target(), label + ".target", "STEP", errors);
            GeneratedActionOnIngredientDTO ingredient = ingredient(operation.ingredient(), label + ".ingredient", errors);
            if (step == null || ingredient == null) return;
            if (!CUSTOM_ID.equals(ingredient.getIngredientId()) && findIngredient(step, ingredient.getIngredientId()) != null) {
                errors.add(label + ": step " + operation.target() + " already has ingredient " + ingredient.getIngredientId() + "; use UPDATE_INGREDIENT to change it");
                return;
            }
            step.getActionOn().getIngredients().add(ingredient);
            translated.add(ProcessEditOperationDTO.builder()
                    .op("ADD_INGREDIENT").target(ref(operation.target())).ingredient(RecipeProcessEditContext.copy(ingredient)).build());
        }

        private void updateIngredient(RecipeProcessEditOutput.Operation operation, String label, List<String> errors) {
            GeneratedRecipeStepDTO step = existing(operation.target(), label + ".target", "STEP", errors);
            GeneratedActionOnIngredientDTO values = ingredient(operation.ingredient(), label + ".ingredient", errors);
            if (step == null || values == null) return;
            GeneratedActionOnIngredientDTO current = presentIngredient(step, values.getIngredientId(), operation.target(), label, errors);
            if (current == null) return;
            if (values.getQuantity() != null) current.setQuantity(values.getQuantity());
            if (values.getUnit() != null) current.setUnit(values.getUnit());
            if (values.getPreparationStyle() != null) current.setPreparationStyle(values.getPreparationStyle());
            if (values.getCustomIngredientName() != null) current.setCustomIngredientName(values.getCustomIngredientName());
            translated.add(ProcessEditOperationDTO.builder()
                    .op("UPDATE_INGREDIENT").target(ref(operation.target())).ingredientId(values.getIngredientId()).ingredient(values).build());
        }

        private void removeIngredient(RecipeProcessEditOutput.Operation operation, String label, List<String> errors) {
            GeneratedRecipeStepDTO step = existing(operation.target(), label + ".target", "STEP", errors);
            if (step == null) return;
            if (isBlank(operation.ingredientId())) {
                errors.add(label + ".ingredientId is required");
                return;
            }
            String ingredientId = normalizer.resolveIngredientId(operation.ingredientId());
            GeneratedActionOnIngredientDTO current = presentIngredient(step, ingredientId, operation.target(), label, errors);
            if (current == null) return;
            step.getActionOn().getIngredients().remove(current);
            translated.add(ProcessEditOperationDTO.builder()
                    .op("REMOVE_INGREDIENT").target(ref(operation.target())).ingredientId(ingredientId).build());
        }

        /** Without a target, replaces the ingredient in every step that uses it — emitted as one operation per step. */
        private void replaceIngredient(RecipeProcessEditOutput.Operation operation, String label, List<String> errors) {
            GeneratedActionOnIngredientDTO replacement = ingredient(operation.ingredient(), label + ".ingredient", errors);
            if (isBlank(operation.from())) {
                errors.add(label + ".from is required");
                return;
            }
            if (replacement == null) return;
            String from = normalizer.resolveIngredientId(operation.from());

            List<String> targets = new ArrayList<>();
            if (!isBlank(operation.target())) {
                GeneratedRecipeStepDTO step = existing(operation.target(), label + ".target", "STEP", errors);
                if (step == null || presentIngredient(step, from, operation.target(), label, errors) == null) return;
                targets.add(operation.target());
            } else {
                for (GeneratedRecipeStepDTO step : steps) {
                    if ("STEP".equals(step.getNodeType()) && findIngredient(step, from) != null) targets.add(step.getStepId());
                }
                if (targets.isEmpty()) {
                    errors.add(label + ": no step uses ingredient " + from);
                    return;
                }
            }

            for (String alias : targets) {
                GeneratedRecipeStepDTO step = steps.get(indexOf(alias));
                List<GeneratedActionOnIngredientDTO> ingredients = step.getActionOn().getIngredients();
                GeneratedActionOnIngredientDTO current = findIngredient(step, from);
                // Keep the old amount (quantity and unit travel together) and style unless the model gave new ones.
                boolean keepAmount = replacement.getQuantity() == null && replacement.getUnit() == null;
                GeneratedActionOnIngredientDTO resolved = new GeneratedActionOnIngredientDTO(
                        replacement.getIngredientId(),
                        keepAmount ? current.getQuantity() : replacement.getQuantity(),
                        keepAmount ? current.getUnit() : replacement.getUnit(),
                        replacement.getPreparationStyle() != null ? replacement.getPreparationStyle() : current.getPreparationStyle(),
                        replacement.getCustomIngredientName(),
                        keepAmount ? current.getCustomUnit() : replacement.getCustomUnit(),
                        replacement.getPreparationStyle() != null ? replacement.getCustomPreparationStyle() : current.getCustomPreparationStyle(),
                        current.getNotes());
                ingredients.set(ingredients.indexOf(current), resolved);
                translated.add(ProcessEditOperationDTO.builder()
                        .op("REPLACE_INGREDIENT").target(ref(alias)).ingredientId(from)
                        .ingredient(RecipeProcessEditContext.copy(resolved)).build());
            }
        }

        private void deleteNode(RecipeProcessEditOutput.Operation operation, String label, List<String> errors) {
            if (existing(operation.target(), label + ".target", null, errors) == null) return;
            detach(operation.target());
            deleted.add(operation.target());
            // Nothing may keep using the removed step's output (the frontend strips these references too).
            for (GeneratedRecipeStepDTO step : steps) {
                step.getActionOn().getSteps().remove(operation.target());
            }
            translated.add(ProcessEditOperationDTO.builder().op("DELETE_NODE").target(ref(operation.target())).build());
        }

        private void moveNode(RecipeProcessEditOutput.Operation operation, String label, List<String> errors) {
            GeneratedRecipeStepDTO node = existing(operation.target(), label + ".target", null, errors);
            if (node == null) return;
            if (Objects.equals(operation.target(), operation.after())) {
                errors.add(label + ".after cannot be the node being moved");
                return;
            }
            if (NEW_FLOW.equals(operation.after())) {
                errors.add(label + ".after NEW_FLOW is only for ADD_STEP");
                return;
            }
            int originalIndex = indexOf(operation.target());
            detach(operation.target());
            Integer insertAt = insertionIndex(operation.after(), label + ".after", errors);
            if (insertAt == null) {
                steps.add(originalIndex, node);
                return;
            }
            steps.add(insertAt, node);
            translated.add(ProcessEditOperationDTO.builder()
                    .op("MOVE_NODE").target(ref(operation.target())).after(ref(operation.after())).build());
        }

        // --- helpers ---

        private void detach(String alias) {
            steps.remove(indexOf(alias));
        }

        private int indexOf(String alias) {
            for (int i = 0; i < steps.size(); i++) {
                if (Objects.equals(steps.get(i).getStepId(), alias)) return i;
            }
            return -1;
        }

        /** The node {@code alias} names right now, or null (with an error) when it doesn't exist or has the wrong kind. */
        private GeneratedRecipeStepDTO existing(String alias, String label, String requiredType, List<String> errors) {
            if (isBlank(alias)) {
                errors.add(label + " is required");
                return null;
            }
            if (deleted.contains(alias)) {
                errors.add(label + " " + alias + " was already removed by an earlier operation");
                return null;
            }
            int index = indexOf(alias);
            if (index < 0) {
                errors.add(label + " references an unknown node: " + alias
                        + " (use an id from CURRENT PROCESS or the stepId of a node added by an earlier operation)");
                return null;
            }
            GeneratedRecipeStepDTO node = steps.get(index);
            if (requiredType != null && !requiredType.equals(node.getNodeType())) {
                errors.add(label + " " + alias + " is a " + node.getNodeType() + ", not a " + requiredType
                        + ("STEP".equals(requiredType) ? " (use UPDATE_CONDITION for a check)" : " (use UPDATE_STEP for a step)"));
                return null;
            }
            return node;
        }

        /** Where a node inserted "after" {@code after} goes; null (with an error) for an unusable anchor. */
        private Integer insertionIndex(String after, String label, List<String> errors) {
            if (START.equals(after)) return 0;
            // A new, unconnected flow: listed last, so it reads after every existing flow.
            if (NEW_FLOW.equals(after)) return steps.size();
            return existing(after, label, null, errors) == null ? null : indexOf(after) + 1;
        }

        private String newAlias(String requested, String label, List<String> errors) {
            String alias = requested;
            if (!isBlank(alias)) {
                if (refByAlias.containsKey(alias) || START.equals(alias) || NEW_FLOW.equals(alias)) {
                    errors.add(label + " " + alias + " is already used; pick a new id such as n" + (added + 1));
                    return null;
                }
            } else {
                int candidate = added + 1;
                while (refByAlias.containsKey("n" + candidate)) candidate++;
                alias = "n" + candidate;
            }
            added++;
            refByAlias.put(alias, NEW_REF_PREFIX + added);
            return alias;
        }

        private GeneratedActionOnIngredientDTO ingredient(RecipeProcessOutput.Ingredient ingredient, String label, List<String> errors) {
            if (ingredient == null || isBlank(ingredient.ingredientId())) {
                errors.add(label + ".ingredientId is required");
                return null;
            }
            return normalizer.toIngredient(ingredient);
        }

        private GeneratedActionOnIngredientDTO presentIngredient(
                GeneratedRecipeStepDTO step, String ingredientId, String alias, String label, List<String> errors
        ) {
            GeneratedActionOnIngredientDTO found = findIngredient(step, ingredientId);
            if (found == null) {
                List<String> present = step.getActionOn().getIngredients().stream()
                        .filter(Objects::nonNull).map(GeneratedActionOnIngredientDTO::getIngredientId).toList();
                errors.add(label + ": step " + alias + " has no ingredient " + ingredientId + " (it has: "
                        + (present.isEmpty() ? "none" : String.join(", ", present)) + ")");
            }
            return found;
        }

        private GeneratedActionOnIngredientDTO findIngredient(GeneratedRecipeStepDTO step, String ingredientId) {
            return step.getActionOn().getIngredients().stream()
                    .filter(ingredient -> ingredient != null && Objects.equals(ingredient.getIngredientId(), ingredientId))
                    .findFirst().orElse(null);
        }

        private String ref(String alias) {
            return START.equals(alias) || NEW_FLOW.equals(alias) ? alias : refByAlias.getOrDefault(alias, alias);
        }

        private List<String> nodeRefs(List<String> aliases) {
            return aliases.stream().map(this::ref).toList();
        }

        private List<String> processRefs(List<String> aliases) {
            return aliases.stream().map(alias -> {
                String processId = context.processId(alias);
                return processId != null ? processId : alias;
            }).toList();
        }

        /** A simulated node re-keyed for the frontend: its ref as stepId, node refs and process ids in actionOn. */
        private GeneratedRecipeStepDTO toFrontend(GeneratedRecipeStepDTO node) {
            GeneratedRecipeStepDTO copy = RecipeProcessEditContext.copy(node);
            copy.setStepId(ref(node.getStepId()));
            copy.getActionOn().setSteps(nodeRefs(copy.getActionOn().getSteps()));
            copy.getActionOn().setProcesses(processRefs(copy.getActionOn().getProcesses()));
            copy.setActionDescription(frontendDescription(copy.getActionDescription()));
            return copy;
        }

        /**
         * A description's annotations in the editor's terms: "s*"/"n*" aliases become node refs, "p*" aliases the
         * stored "processId:stepId". One naming an alias this edit doesn't know reads as plain words.
         */
        private String frontendDescription(String description) {
            return StepAnnotations.remapIds(description, annotation -> switch (annotation.kind()) {
                case StepAnnotations.STEP -> refByAlias.containsKey(annotation.id()) ? ref(annotation.id()) : null;
                case StepAnnotations.PREPARED -> context.processId(annotation.id());
                default -> annotation.id();
            });
        }
    }

    /** The frontend's name for a clearable step field: the model's "uses" is the editor's subprocess list ("processes"). */
    private static String frontendFieldName(String field) {
        return "uses".equals(field) ? "processes" : field;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
