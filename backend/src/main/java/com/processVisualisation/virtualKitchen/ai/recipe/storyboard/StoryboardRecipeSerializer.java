package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationInput;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService.ProcessStepPreparation;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService.StepContext;
import com.processVisualisation.virtualKitchen.recipe.model.PreparationFlows;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessNodeKind;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Writes a whole recipe (its main process and its Preparation) as the one JSON text the storyboard planner reads:
 * every step with its resolved action, ingredients, description and outcome, every check with its question, how the
 * nodes follow one another, which preparation flow makes which component, and which main step uses it.
 * <p>
 * Node ids are replaced by short aliases ({@code p1..} preparation steps, {@code m1..} main steps, {@code c1..}
 * checks), so the model has short ids to copy and cannot damage a real one; {@link StoryboardSource#nodes()} maps
 * them back.
 */
public final class StoryboardRecipeSerializer {

    private static final Logger log = LoggerFactory.getLogger(StoryboardRecipeSerializer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONDITION_YES_HANDLE = "condition-yes";

    private StoryboardRecipeSerializer() {
    }

    /**
     * @param dishName the recipe's name
     * @param processes the recipe's processes with their steps in cooking order
     *                  ({@code RecipeProcessVisualizationService.prepareStepContexts})
     */
    public static StoryboardSource serialize(String dishName, List<ProcessStepPreparation> processes) {
        List<ProcessStepPreparation> preparations = processes.stream()
                .filter(prepared -> prepared.process().getType() == ProcessType.SUBPROCESS).toList();
        List<ProcessStepPreparation> mains = processes.stream()
                .filter(prepared -> prepared.process().getType() != ProcessType.SUBPROCESS).toList();

        // Pass 1: every node gets its alias, in the order it will be listed.
        Aliases aliases = new Aliases();
        List<FlowNodes> flows = new ArrayList<>();
        for (ProcessStepPreparation preparation : preparations) {
            for (PreparationFlows.Flow flow : PreparationFlows.flowsOf(preparation.process())) {
                Set<String> ids = new LinkedHashSet<>();
                flow.nodes().forEach(node -> ids.add(node.getId()));
                List<Listed> listed = listNodes(preparation, ids);
                if (listed.stream().noneMatch(Listed::isStep)) continue;
                listed.forEach(node -> aliases.assign(node, node.isStep() ? "p" : "c"));
                flows.add(new FlowNodes(preparation.process(), flow, listed));
            }
        }
        List<FlowNodes> mainLists = new ArrayList<>();
        for (ProcessStepPreparation main : mains) {
            List<Listed> listed = listNodes(main, null);
            listed.forEach(node -> aliases.assign(node, node.isStep() ? "m" : "c"));
            mainLists.add(new FlowNodes(main.process(), null, listed));
        }

        // Pass 2: the JSON, and the way back from each alias.
        Map<String, StoryboardSource.Node> nodes = new LinkedHashMap<>();
        List<Map<String, Object>> flowJson = new ArrayList<>();
        int order = 0;
        for (FlowNodes flow : flows) {
            List<Map<String, Object>> nodeJson = new ArrayList<>();
            for (Listed node : flow.nodes()) {
                nodeJson.add(describe(node, flow.process(), aliases, flows));
                nodes.put(aliases.of(node), toNode(node, flow.process(), StoryboardSource.PREPARATION, order++, aliases, flows));
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            if (StringUtils.hasText(flow.flow().outputName())) entry.put("output", flow.flow().outputName());
            entry.put("nodes", nodeJson);
            flowJson.add(entry);
        }
        List<Map<String, Object>> mainJson = new ArrayList<>();
        order = 0;
        for (FlowNodes main : mainLists) {
            for (Listed node : main.nodes()) {
                mainJson.add(describe(node, main.process(), aliases, flows));
                nodes.put(aliases.of(node), toNode(node, main.process(), StoryboardSource.MAIN, order++, aliases, flows));
            }
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("dish", dishName == null ? "" : dishName.trim());
        if (!flowJson.isEmpty()) root.put("preparation", Map.of("flows", flowJson));
        root.put("main", Map.of("nodes", mainJson));
        String json;
        try {
            json = MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the recipe for the storyboard", e);
        }
        return new StoryboardSource(dishName, json, sha256(json), nodes);
    }

    /** One node as listed: its process node, and for a step its resolved, readable content. */
    private record Listed(Process.ProcessNode node, RecipeProcessVisualizationInput input) {
        boolean isStep() {
            return node.getKind() != ProcessNodeKind.CONDITION;
        }
    }

    private record FlowNodes(Process process, PreparationFlows.Flow flow, List<Listed> nodes) {
    }

    private static final class Aliases {
        private final Map<String, String> byNodeId = new HashMap<>();
        private final Map<String, Integer> counters = new HashMap<>();

        void assign(Listed node, String prefix) {
            int next = counters.merge(prefix, 1, Integer::sum);
            byNodeId.put(node.node().getId(), prefix + next);
        }

        String of(Listed node) {
            return byNodeId.get(node.node().getId());
        }

        String ofId(String nodeId) {
            return nodeId == null ? null : byNodeId.get(nodeId);
        }
    }

    /**
     * The nodes of a process (or of the part of it in {@code only}): its steps in cooking order, each check placed
     * right after the last step that leads to it.
     */
    private static List<Listed> listNodes(ProcessStepPreparation prepared, Set<String> only) {
        Process process = prepared.process();
        List<Listed> listed = new ArrayList<>();
        for (StepContext context : prepared.steps()) {
            if (only == null || only.contains(context.node().getId())) listed.add(new Listed(context.node(), context.input()));
        }
        List<Process.ProcessNode> conditions = nodesOf(process).stream()
                .filter(node -> node.getKind() == ProcessNodeKind.CONDITION)
                .filter(node -> only == null || only.contains(node.getId()))
                .toList();
        for (Process.ProcessNode condition : conditions) {
            int after = -1;
            for (Process.ProcessEdge edge : edgesOf(process)) {
                if (!Objects.equals(edge.getTarget(), condition.getId())) continue;
                for (int i = 0; i < listed.size(); i++) {
                    if (listed.get(i).isStep() && listed.get(i).node().getId().equals(edge.getSource())) after = Math.max(after, i);
                }
            }
            listed.add(after < 0 ? listed.size() : after + 1, new Listed(condition, null));
        }
        return listed;
    }

    private static Map<String, Object> describe(Listed listed, Process process, Aliases aliases, List<FlowNodes> flows) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", aliases.of(listed));
        if (listed.isStep()) {
            RecipeProcessVisualizationInput input = listed.input();
            put(json, "action", input.action());
            List<String> ingredients = input.ingredients() == null ? List.of() : input.ingredients().stream()
                    .map(StoryboardRecipeSerializer::ingredientLine).filter(StringUtils::hasText).toList();
            if (!ingredients.isEmpty()) json.put("ingredients", ingredients);
            List<Map<String, Object>> uses = new ArrayList<>();
            for (String alias : usesOf(listed.node(), aliases, flows)) {
                Map<String, Object> use = new LinkedHashMap<>();
                use.put("id", alias);
                String name = outputNameOf(alias, aliases, flows);
                if (StringUtils.hasText(name)) use.put("name", name);
                uses.add(use);
            }
            if (!uses.isEmpty()) json.put("uses", uses);
            put(json, "description", input.actionDescription());
            put(json, "expectedOutput", input.expectedOutput());
            put(json, "flame", input.flameLevel());
            put(json, "temperature", input.temperature());
            put(json, "duration", input.duration());
        } else {
            json.put("kind", "CONDITION");
            Map<String, Object> data = dataOf(listed.node());
            Map<?, ?> condition = data.get("condition") instanceof Map<?, ?> nested ? nested : Map.of();
            String question = text(condition.get("question"));
            put(json, "question", StringUtils.hasText(question) ? question : text(data.get("title")));
            put(json, "notes", text(condition.get("notes")));
        }
        List<String> next = new ArrayList<>();
        for (Process.ProcessEdge edge : edgesOf(process)) {
            if (!Objects.equals(edge.getSource(), listed.node().getId())) continue;
            String target = aliases.ofId(edge.getTarget());
            if (target == null) continue;
            if (!listed.isStep() && CONDITION_YES_HANDLE.equals(edge.getSourceHandle())) {
                json.put("yes", target);
            } else if (!listed.isStep() && PreparationFlows.CONDITION_NO_HANDLE.equals(edge.getSourceHandle())) {
                json.put("no", target);
            } else if (!next.contains(target)) {
                next.add(target);
            }
        }
        if (!next.isEmpty()) json.put("next", next);
        return json;
    }

    private static StoryboardSource.Node toNode(Listed listed, Process process, String group, int order, Aliases aliases,
                                                List<FlowNodes> flows) {
        return new StoryboardSource.Node(aliases.of(listed), process.getId(), listed.node().getId(), group, listed.isStep(),
                order, listed.isStep() ? usesOf(listed.node(), aliases, flows) : List.of(), hasImage(listed.node()));
    }

    /**
     * Aliases of the preparation steps a step brings in ({@code actionOn.processes[{processId, stepId}]}). A reference
     * to a whole process (older data) stands for the outputs of all its flows; a reference that leads nowhere is left out.
     */
    private static List<String> usesOf(Process.ProcessNode node, Aliases aliases, List<FlowNodes> flows) {
        Map<String, Object> data = dataOf(node);
        Map<?, ?> step = data.get("step") instanceof Map<?, ?> nested ? nested : data;
        if (!(step.get("actionOn") instanceof Map<?, ?> actionOn) || !(actionOn.get("processes") instanceof List<?> references)) {
            return List.of();
        }
        List<String> uses = new ArrayList<>();
        for (Object entry : references) {
            if (!(entry instanceof Map<?, ?> reference) || !(reference.get("processId") instanceof Number processId)) continue;
            String stepId = text(reference.get("stepId"));
            if (StringUtils.hasText(stepId)) {
                String alias = aliases.ofId(stepId);
                if (alias == null) {
                    log.debug("event=storyboard_reference_skipped reason=unknown_step processId={}", processId);
                } else if (!uses.contains(alias)) {
                    uses.add(alias);
                }
                continue;
            }
            for (FlowNodes flow : flows) {
                if (flow.process().getId() == null || flow.process().getId() != processId.longValue()) continue;
                String alias = flow.flow().output() == null ? null : aliases.ofId(flow.flow().output().getId());
                if (alias != null && !uses.contains(alias)) uses.add(alias);
            }
        }
        return uses;
    }

    /** The named output of the preparation step behind {@code alias}: its Expected Output, else its flow's output. */
    private static String outputNameOf(String alias, Aliases aliases, List<FlowNodes> flows) {
        for (FlowNodes flow : flows) {
            for (Listed listed : flow.nodes()) {
                if (!alias.equals(aliases.of(listed))) continue;
                String own = PreparationFlows.expectedOutputOf(listed.node());
                return StringUtils.hasText(own) ? own : flow.flow().outputName();
            }
        }
        return "";
    }

    private static String ingredientLine(RecipeProcessVisualizationInput.IngredientTarget ingredient) {
        StringBuilder line = new StringBuilder();
        if (StringUtils.hasText(ingredient.quantity())) line.append(ingredient.quantity().trim()).append(' ');
        if (StringUtils.hasText(ingredient.unit())) line.append(ingredient.unit().trim()).append(' ');
        if (StringUtils.hasText(ingredient.name())) line.append(ingredient.name().trim());
        if (StringUtils.hasText(ingredient.preparationStyle())) line.append(" (").append(ingredient.preparationStyle().trim()).append(')');
        return line.toString().trim();
    }

    private static boolean hasImage(Process.ProcessNode node) {
        Map<String, Object> data = dataOf(node);
        if (data.get("imageUrl") instanceof String url && StringUtils.hasText(url)) return true;
        return data.get("visualization") instanceof Map<?, ?> nested
                && nested.get("imageUrl") instanceof String url && StringUtils.hasText(url);
    }

    private static void put(Map<String, Object> json, String key, String value) {
        if (StringUtils.hasText(value)) json.put(key, value.trim());
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Map<String, Object> dataOf(Process.ProcessNode node) {
        return node.getData() == null ? Map.of() : node.getData();
    }

    private static List<Process.ProcessNode> nodesOf(Process process) {
        return process.getNodes() == null ? List.of() : process.getNodes().stream().filter(Objects::nonNull).toList();
    }

    private static List<Process.ProcessEdge> edgesOf(Process process) {
        return process.getEdges() == null ? List.of() : process.getEdges().stream().filter(Objects::nonNull).toList();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
