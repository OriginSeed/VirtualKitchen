package com.processVisualisation.virtualKitchen.ai.recipe.edit;

import com.processVisualisation.virtualKitchen.ai.recipe.edit.dto.EditSubprocessRefDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.edit.dto.EditTargetNodeDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.edit.dto.EditTargetProcessDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.RecipeProcessGenerationValidator;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedActionOnDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedActionOnIngredientDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.GeneratedRecipeStepDTO;
import com.processVisualisation.virtualKitchen.recipe.model.StepAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The process an EDIT request targets, re-keyed for the model. Real node ids (UUIDs) cost tokens and get mangled by
 * models, so every node gets a short alias in reading order ("s1", "s2", ...) and every other process of the recipe
 * one too ("p1", ...). {@link #steps()} is the process as a {@link GeneratedRecipeStepDTO} list keyed by those aliases
 * (the form {@link RecipeProcessGenerationValidator} checks), and the maps translate the model's answer back.
 */
final class RecipeProcessEditContext {

    private final Long processId;
    private final String processName;
    private final List<GeneratedRecipeStepDTO> steps;
    private final Map<String, String> nodeIdByAlias;
    private final Map<String, String> aliasByNodeId;
    private final Map<String, String> processIdByAlias;
    private final Map<String, String> processNameByAlias;
    private final Map<String, EditTargetNodeDTO> nodeByAlias;
    private final String selectedAlias;

    private RecipeProcessEditContext(
            Long processId, String processName, List<GeneratedRecipeStepDTO> steps,
            Map<String, String> nodeIdByAlias, Map<String, String> aliasByNodeId,
            Map<String, String> processIdByAlias, Map<String, String> processNameByAlias,
            Map<String, EditTargetNodeDTO> nodeByAlias, String selectedAlias
    ) {
        this.processId = processId;
        this.processName = processName;
        this.steps = steps;
        this.nodeIdByAlias = nodeIdByAlias;
        this.aliasByNodeId = aliasByNodeId;
        this.processIdByAlias = processIdByAlias;
        this.processNameByAlias = processNameByAlias;
        this.nodeByAlias = nodeByAlias;
        this.selectedAlias = selectedAlias;
    }

    static RecipeProcessEditContext of(EditTargetProcessDTO target, String selectedNodeId) {
        Map<String, String> nodeIdByAlias = new LinkedHashMap<>();
        Map<String, String> aliasByNodeId = new LinkedHashMap<>();
        Map<String, EditTargetNodeDTO> nodeByAlias = new LinkedHashMap<>();
        List<EditTargetNodeDTO> nodes = target.getNodes() == null ? List.of() : target.getNodes().stream().filter(Objects::nonNull).toList();
        for (EditTargetNodeDTO node : nodes) {
            if (node.getNodeId() == null || aliasByNodeId.containsKey(node.getNodeId())) continue;
            String alias = "s" + (nodeIdByAlias.size() + 1);
            nodeIdByAlias.put(alias, node.getNodeId());
            aliasByNodeId.put(node.getNodeId(), alias);
            nodeByAlias.put(alias, node);
        }

        Map<String, String> processIdByAlias = new LinkedHashMap<>();
        Map<String, String> aliasByProcessId = new LinkedHashMap<>();
        Map<String, String> processNameByAlias = new LinkedHashMap<>();
        List<EditSubprocessRefDTO> subprocesses = target.getSubprocesses() == null ? List.of() : target.getSubprocesses();
        for (EditSubprocessRefDTO subprocess : subprocesses) {
            if (subprocess == null || subprocess.getProcessId() == null) continue;
            // A step output is keyed "processId:stepId" — the form a step's actionOn.processes entries arrive in.
            boolean wholeProcess = subprocess.getStepId() == null || subprocess.getStepId().isBlank();
            String id = wholeProcess ? String.valueOf(subprocess.getProcessId()) : subprocess.getProcessId() + ":" + subprocess.getStepId().trim();
            if (aliasByProcessId.containsKey(id) || (wholeProcess && Objects.equals(subprocess.getProcessId(), target.getProcessId()))) continue;
            String alias = "p" + (processIdByAlias.size() + 1);
            processIdByAlias.put(alias, id);
            aliasByProcessId.put(id, alias);
            processNameByAlias.put(alias, subprocess.getName());
        }

        List<GeneratedRecipeStepDTO> steps = new ArrayList<>();
        nodeByAlias.forEach((alias, node) -> {
            GeneratedRecipeStepDTO step = copy(node.getContent());
            step.setStepId(alias);
            if (step.getNodeType() == null || step.getNodeType().isBlank()) step.setNodeType("STEP");
            if (step.getExpectedOutput() == null) step.setExpectedOutput("");
            step.getActionOn().setSteps(translate(step.getActionOn().getSteps(), aliasByNodeId));
            step.getActionOn().setProcesses(translate(step.getActionOn().getProcesses(), aliasByProcessId));
            step.setActionDescription(toModelAnnotations(step.getActionDescription(), aliasByNodeId, aliasByProcessId));
            steps.add(step);
        });

        String selectedAlias = selectedNodeId == null ? null : aliasByNodeId.get(selectedNodeId);
        return new RecipeProcessEditContext(target.getProcessId(), target.getName(), Collections.unmodifiableList(steps),
                nodeIdByAlias, aliasByNodeId, processIdByAlias, processNameByAlias, nodeByAlias, selectedAlias);
    }

    Long processId() {
        return processId;
    }

    String processName() {
        return processName;
    }

    /** The process's nodes in reading order, keyed by alias. Callers must {@link #copy} one before mutating it. */
    List<GeneratedRecipeStepDTO> steps() {
        return steps;
    }

    boolean isNodeAlias(String alias) {
        return nodeIdByAlias.containsKey(alias);
    }

    String nodeId(String alias) {
        return nodeIdByAlias.get(alias);
    }

    /** Alias of a connection target, or null when it points outside the serialised process. */
    String aliasOfNodeId(String nodeId) {
        return nodeId == null ? null : aliasByNodeId.get(nodeId);
    }

    EditTargetNodeDTO node(String alias) {
        return nodeByAlias.get(alias);
    }

    Map<String, String> processNameByAlias() {
        return processNameByAlias;
    }

    /** The real process id (as a string) behind a "p*" alias, or null for an unknown one. */
    String processId(String alias) {
        return processIdByAlias.get(alias);
    }

    String selectedAlias() {
        return selectedAlias;
    }

    /** Deep copy with mutable lists, so an edit simulation never touches the request's own objects. */
    static GeneratedRecipeStepDTO copy(GeneratedRecipeStepDTO source) {
        GeneratedRecipeStepDTO copy = new GeneratedRecipeStepDTO();
        if (source == null) {
            copy.setActionOn(new GeneratedActionOnDTO(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
            return copy;
        }
        copy.setNodeType(source.getNodeType());
        copy.setStepId(source.getStepId());
        copy.setAction(source.getAction());
        copy.setCustomActionName(source.getCustomActionName());
        GeneratedActionOnDTO actionOn = source.getActionOn();
        copy.setActionOn(new GeneratedActionOnDTO(
                actionOn == null || actionOn.getIngredients() == null ? new ArrayList<>()
                        : new ArrayList<>(actionOn.getIngredients().stream().map(RecipeProcessEditContext::copy).toList()),
                actionOn == null || actionOn.getProcesses() == null ? new ArrayList<>() : new ArrayList<>(actionOn.getProcesses()),
                actionOn == null || actionOn.getSteps() == null ? new ArrayList<>() : new ArrayList<>(actionOn.getSteps())
        ));
        copy.setTemperatureValue(source.getTemperatureValue());
        copy.setTemperatureUnit(source.getTemperatureUnit());
        copy.setTemperature(source.getTemperature());
        copy.setFlameLevel(source.getFlameLevel());
        copy.setDuration(source.getDuration());
        copy.setRepeatInterval(source.getRepeatInterval());
        copy.setTitle(source.getTitle());
        copy.setExpectedResult(source.getExpectedResult());
        copy.setActionDescription(source.getActionDescription());
        copy.setExpectedOutput(source.getExpectedOutput());
        copy.setSummary(source.getSummary());
        return copy;
    }

    static GeneratedActionOnIngredientDTO copy(GeneratedActionOnIngredientDTO source) {
        if (source == null) return null;
        return new GeneratedActionOnIngredientDTO(source.getIngredientId(), source.getQuantity(), source.getUnit(),
                source.getPreparationStyle(), source.getCustomIngredientName(), source.getCustomUnit(),
                source.getCustomPreparationStyle(), source.getNotes());
    }

    /**
     * A description's annotations in the model's terms: a prepared component as its "p*" alias, an earlier step as its
     * "s*" alias. One that points at nothing the model is shown reads as plain words.
     */
    private static String toModelAnnotations(String description, Map<String, String> aliasByNodeId, Map<String, String> aliasByProcessId) {
        return StepAnnotations.remapIds(description, annotation -> switch (annotation.kind()) {
            case StepAnnotations.PREPARED -> aliasByProcessId.get(annotation.id());
            case StepAnnotations.STEP -> aliasByNodeId.get(annotation.id());
            default -> annotation.id();
        });
    }

    /** Maps each id through {@code mapping}, dropping ids it does not know (dangling references). */
    private static List<String> translate(List<String> ids, Map<String, String> mapping) {
        List<String> translated = new ArrayList<>();
        if (ids == null) return translated;
        for (String id : ids) {
            String mapped = id == null ? null : mapping.get(id);
            if (mapped != null) translated.add(mapped);
        }
        return translated;
    }
}
