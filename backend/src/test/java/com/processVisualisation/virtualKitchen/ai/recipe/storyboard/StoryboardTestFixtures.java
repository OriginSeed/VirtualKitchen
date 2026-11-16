package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.RecipeStoryboard;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationInput;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService.ProcessStepPreparation;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService.StepContext;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessNodeKind;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessType;
import com.processVisualisation.virtualKitchen.recipe.service.ProcessStepOrder;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Recipes and plans for the storyboard tests. */
final class StoryboardTestFixtures {

    static final long MAIN_ID = 1L;
    static final long PREP_ID = 2L;

    private StoryboardTestFixtures() {
    }

    /** The saved chicken curry: a main process with a check that loops back, and a one-step Preparation it uses. */
    static List<Process> chickenCurry() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (InputStream in = StoryboardTestFixtures.class.getResourceAsStream("/storyboard/chicken-curry-processes.json")) {
            return Arrays.asList(mapper.readValue(in, Process[].class));
        }
    }

    /** Each process with its steps in cooking order, the content read straight from the step data. */
    static List<ProcessStepPreparation> prepared(List<Process> processes) {
        List<ProcessStepPreparation> prepared = new ArrayList<>();
        for (Process process : processes) {
            List<StepContext> steps = new ArrayList<>();
            for (Process.ProcessNode node : ProcessStepOrder.orderedSteps(process)) {
                Map<?, ?> step = node.getData().get("step") instanceof Map<?, ?> nested ? nested : node.getData();
                steps.add(new StepContext(node, new RecipeProcessVisualizationInput(
                        text(step.get("action")), List.of(), List.of(),
                        // As the visualization service does: annotations read as their names, never as ids.
                        com.processVisualisation.virtualKitchen.recipe.model.StepAnnotations.toPlainText(
                                text(step.get("actionDescription"))),
                        text(step.get("expectedOutput")), "", "", "", null)));
            }
            prepared.add(new ProcessStepPreparation(process, steps));
        }
        return prepared;
    }

    static StoryboardSource source(List<Process> processes) {
        return StoryboardRecipeSerializer.serialize("Test dish", prepared(processes));
    }

    /**
     * A biryani-like recipe: three preparation flows (marinade: mix, coat, rest; rice: boil, drain; onions: slice,
     * fry) and a main process that uses all three, with a check that loops back to the cooking step.
     */
    static List<Process> layeredRecipe() {
        Process preparation = process(PREP_ID, ProcessType.SUBPROCESS, "Preparation");
        add(preparation, step("mix", "mix", "Marinade"), step("coat", "coat", "Marinated chicken"), step("rest", "rest", ""));
        edge(preparation, "mix", "coat", null);
        edge(preparation, "coat", "rest", null);
        add(preparation, step("boil", "boil", "Parboiled rice"), step("drain", "drain", "Drained rice"));
        edge(preparation, "boil", "drain", null);
        add(preparation, step("slice", "slice", "Sliced onions"), step("fry", "fry", "Fried onions"));
        edge(preparation, "slice", "fry", null);

        Process main = process(MAIN_ID, ProcessType.MAIN, "Main");
        add(main, step("heat", "heat", "Hot oil"),
                uses(step("addChicken", "add", "Chicken in the pot"), "coat"),
                step("cook", "cook", "Cooked chicken"),
                condition("check", "Is the chicken cooked?"),
                uses(step("layer", "layer", "Layered pot"), "drain", "fry"),
                step("dum", "simmer", "Biryani"));
        edge(main, "heat", "addChicken", null);
        edge(main, "addChicken", "cook", null);
        edge(main, "cook", "check", null);
        edge(main, "check", "layer", "condition-yes");
        edge(main, "check", "cook", "condition-no");
        edge(main, "layer", "dum", null);
        return List.of(main, preparation);
    }

    static Process process(long id, ProcessType type, String name) {
        Process process = new Process();
        process.setId(id);
        process.setType(type);
        process.setName(name);
        process.setRecipeId(62L);
        return process;
    }

    static void add(Process process, Process.ProcessNode... nodes) {
        process.getNodes().addAll(List.of(nodes));
    }

    static Process.ProcessNode step(String id, String action, String expectedOutput) {
        Process.ProcessNode node = new Process.ProcessNode();
        node.setId(id);
        node.setKind(ProcessNodeKind.STEP);
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("action", action);
        step.put("actionDescription", "Do " + id);
        step.put("expectedOutput", expectedOutput);
        node.getData().put("step", step);
        return node;
    }

    @SuppressWarnings("unchecked")
    static Process.ProcessNode uses(Process.ProcessNode node, String... preparationStepIds) {
        List<Map<String, Object>> references = new ArrayList<>();
        for (String stepId : preparationStepIds) {
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("processId", PREP_ID);
            reference.put("stepId", stepId);
            references.add(reference);
        }
        ((Map<String, Object>) node.getData().get("step")).put("actionOn", Map.of("processes", references));
        return node;
    }

    static Process.ProcessNode condition(String id, String question) {
        Process.ProcessNode node = new Process.ProcessNode();
        node.setId(id);
        node.setKind(ProcessNodeKind.CONDITION);
        node.getData().put("title", question);
        node.getData().put("condition", Map.of("question", question));
        return node;
    }

    static void edge(Process process, String source, String target, String sourceHandle) {
        Process.ProcessEdge edge = new Process.ProcessEdge();
        edge.setId(source + "-" + target);
        edge.setSource(source);
        edge.setTarget(target);
        edge.setSourceHandle(sourceHandle);
        process.getEdges().add(edge);
    }

    /** The alias the serializer gave a node. */
    static String alias(StoryboardSource source, String nodeId) {
        return source.nodes().values().stream().filter(node -> node.nodeId().equals(nodeId))
                .map(StoryboardSource.Node::alias).findFirst().orElseThrow();
    }

    static StoryboardPlan.Panel panel(String id, int sequence, String group, List<String> steps, List<String> dependsOn,
                                      List<String> references) {
        return new StoryboardPlan.Panel(id, sequence, group, null, "Panel " + id, steps, "Photo of panel " + id,
                dependsOn, references, List.of(), List.of(), "Done " + id);
    }

    /** A good plan for {@link #layeredRecipe()}: seven panels, the resting step folded into "coat". */
    static StoryboardPlan layeredPlan(StoryboardSource source) {
        return new StoryboardPlan("Biryani", "A layered rice dish", List.of(
                panel("P1", 1, "PREPARATION", List.of(alias(source, "mix")), List.of(), List.of()),
                panel("P2", 2, "PREPARATION", List.of(alias(source, "coat"), alias(source, "rest")), List.of("P1"), List.of("P1")),
                panel("P3", 3, "PREPARATION", List.of(alias(source, "boil")), List.of(), List.of()),
                panel("P4", 4, "PREPARATION", List.of(alias(source, "drain")), List.of("P3"), List.of("P3")),
                panel("P5", 5, "PREPARATION", List.of(alias(source, "slice"), alias(source, "fry")), List.of(), List.of()),
                panel("P6", 6, "MAIN", List.of(alias(source, "heat"), alias(source, "addChicken"), alias(source, "cook"),
                        alias(source, "check")), List.of("P2"), List.of("P2")),
                panel("P7", 7, "MAIN", List.of(alias(source, "layer"), alias(source, "dum")), List.of("P6"), List.of())),
                List.of());
    }

    /** A stored storyboard with {@code panels} panels, each covering one made-up step. */
    static RecipeStoryboard storyboard(int panels) {
        RecipeStoryboard storyboard = new RecipeStoryboard();
        storyboard.setId("sb-1");
        storyboard.setRecipeId(62L);
        storyboard.setUserId(7L);
        storyboard.setTitle("Chicken Curry");
        storyboard.setDishContext("A North Indian home-style curry");
        for (int i = 1; i <= panels; i++) {
            RecipeStoryboard.Panel panel = new RecipeStoryboard.Panel();
            panel.setPanelId("P" + i);
            panel.setSequence(i);
            panel.setGroup(StoryboardSource.MAIN);
            panel.setDescription("Action " + i);
            panel.setImagePrompt("Photo of action " + i);
            panel.setExpectedState("State " + i);
            panel.setSourceStepIds(new ArrayList<>(List.of("step-" + i)));
            storyboard.getPanels().add(panel);
        }
        StoryboardLayout grid = StoryboardLayout.of(panels);
        RecipeStoryboard.Layout layout = new RecipeStoryboard.Layout();
        layout.setRows(grid.rows());
        layout.setColumns(grid.columns());
        storyboard.setLayout(layout);
        return storyboard;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
