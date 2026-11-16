package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.processVisualisation.virtualKitchen.ai.recipe.storyboard.StoryboardTestFixtures.alias;
import static com.processVisualisation.virtualKitchen.ai.recipe.storyboard.StoryboardTestFixtures.layeredRecipe;
import static com.processVisualisation.virtualKitchen.ai.recipe.storyboard.StoryboardTestFixtures.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryboardRecipeSerializerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void chickenCurry_isWrittenWithItsPreparation_itsCheckAndItsLoop() throws Exception {
        List<Process> processes = StoryboardTestFixtures.chickenCurry();
        StoryboardSource source = source(processes);
        JsonNode root = mapper.readTree(source.json());

        // The one preparation flow comes first, named by what it makes.
        JsonNode flows = root.path("preparation").path("flows");
        assertEquals(1, flows.size());
        assertEquals("Marinated Chicken", flows.get(0).path("output").asText());
        assertEquals("p1", flows.get(0).path("nodes").get(0).path("id").asText());

        // Eight main steps and the check, which sits right after the step that leads to it.
        JsonNode main = root.path("main").path("nodes");
        assertEquals(9, main.size());
        assertEquals(List.of("m1", "m2", "m3", "m4", "m5", "m6", "m7", "c1", "m8"), ids(main));
        JsonNode check = main.get(7);
        assertEquals("CONDITION", check.path("kind").asText());
        assertEquals("m8", check.path("yes").asText());
        assertEquals("m7", check.path("no").asText());
        assertEquals("c1", main.get(6).path("next").get(0).asText());

        // The step that adds the marinated chicken names the preparation step that made it.
        JsonNode addChicken = main.get(4);
        assertEquals("p1", addChicken.path("uses").get(0).path("id").asText());
        assertEquals("Marinated Chicken", addChicken.path("uses").get(0).path("name").asText());
        assertEquals(List.of("p1"), source.nodes().get("m5").uses());

        // No real node id reaches the model, and every alias leads back to its node.
        for (Process process : processes) {
            for (Process.ProcessNode node : process.getNodes()) {
                assertFalse(source.json().contains(node.getId()), "real id in the JSON: " + node.getId());
                assertEquals(node.getId(), source.nodes().get(alias(source, node.getId())).nodeId());
            }
        }
        assertEquals(9, source.stepAliases().size());
        assertEquals(10, source.size());
    }

    @Test
    void independentPreparationFlows_areListedOneByOne_andSeveralComponentsCanBeUsedByOneStep() throws Exception {
        StoryboardSource source = source(layeredRecipe());
        JsonNode flows = mapper.readTree(source.json()).path("preparation").path("flows");

        assertEquals(3, flows.size());
        assertEquals(List.of("p1", "p2", "p3"), ids(flows.get(0).path("nodes")));
        assertEquals(List.of("p4", "p5"), ids(flows.get(1).path("nodes")));
        assertEquals(List.of("p6", "p7"), ids(flows.get(2).path("nodes")));
        assertEquals("Drained rice", flows.get(1).path("output").asText());

        StoryboardSource.Node layer = source.nodes().get(alias(source, "layer"));
        assertEquals(List.of(alias(source, "drain"), alias(source, "fry")), layer.uses());
        assertEquals(StoryboardSource.MAIN, layer.group());
        assertEquals(StoryboardSource.PREPARATION, source.nodes().get("p4").group());
        assertEquals(StoryboardTestFixtures.PREP_ID, source.nodes().get("p4").processId());
    }

    @Test
    void aRecipeWithoutPreparation_hasOnlyItsMainSequence() throws Exception {
        Process main = layeredRecipe().get(0);
        StoryboardSource source = source(List.of(main));
        JsonNode root = mapper.readTree(source.json());

        assertTrue(root.path("preparation").isMissingNode());
        assertEquals(6, root.path("main").path("nodes").size());
        // The preparation steps it names no longer exist: the references are left out, not invented.
        assertTrue(source.nodes().get(alias(source, "layer")).uses().isEmpty());
        assertFalse(root.path("main").path("nodes").get(1).has("uses"));
    }

    @Test
    void aReferenceToAWholePreparation_standsForTheOutputsOfItsFlows() {
        List<Process> processes = layeredRecipe();
        Process.ProcessNode heat = processes.get(0).getNodes().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> step = (Map<String, Object>) heat.getData().get("step");
        step.put("actionOn", Map.of("processes", List.of(Map.of("processId", StoryboardTestFixtures.PREP_ID))));

        StoryboardSource source = source(processes);

        // The flow outputs: the marinade flow ends with the resting step, the others with drain and fry.
        assertEquals(List.of(alias(source, "rest"), alias(source, "drain"), alias(source, "fry")),
                source.nodes().get(alias(source, "heat")).uses());
    }

    @Test
    void theHashFollowsTheRecipe_notTheImagesOnItsSteps() {
        String before = source(layeredRecipe()).hash();

        List<Process> withImage = layeredRecipe();
        withImage.get(0).getNodes().get(0).getData().put("imageUrl", "https://images.test/heat.png");
        StoryboardSource illustrated = source(withImage);
        assertEquals(before, illustrated.hash());
        assertTrue(illustrated.nodes().get(alias(illustrated, "heat")).hasImage());

        List<Process> edited = layeredRecipe();
        @SuppressWarnings("unchecked")
        Map<String, Object> step = (Map<String, Object>) edited.get(0).getNodes().get(0).getData().get("step");
        step.put("expectedOutput", "Smoking hot oil");
        assertNotEquals(before, source(edited).hash());
    }

    @Test
    void aStrayMainProcessBesideTheRecipesOwn_isJustMoreMainSteps() {
        // The job service passes one main process; the serializer itself never drops what it is given.
        List<Process> processes = new ArrayList<>(layeredRecipe());
        Process extra = StoryboardTestFixtures.process(9L, ProcessType.MAIN, "Extra");
        StoryboardTestFixtures.add(extra, StoryboardTestFixtures.step("plate", "plate", "Served"));
        processes.add(extra);

        StoryboardSource source = source(processes);

        assertEquals(StoryboardSource.MAIN, source.nodes().get(alias(source, "plate")).group());
        assertEquals(6, source.nodes().get(alias(source, "plate")).order());
    }

    private static List<String> ids(JsonNode nodes) {
        List<String> ids = new ArrayList<>();
        nodes.forEach(node -> ids.add(node.path("id").asText()));
        return ids;
    }
}
