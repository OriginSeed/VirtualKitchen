package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Builds AI prompts for recipe STEP visualization (image only — no video in V1) from
 * {@link RecipeProcessVisualizationInput}: a step's Action On ingredients and subprocess
 * references.
 * <p>
 * Contract: exactly one image per step. When a step targets more than one ingredient (or
 * simultaneous operations), the prompt explicitly asks for a single composite/side-by-side image
 * rather than one image per ingredient.
 */
@Component
public class RecipeProcessVisualizationPromptBuilder {

    public String buildSystemPrompt() {
        return "You are a culinary visualization prompt writer. "
                + "Return strict JSON only: {\"imagePrompt\": \"...\"}. "
                + "No markdown, prose, or code fences.";
    }

    public String buildUserPrompt(RecipeProcessVisualizationInput current) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Describe a photorealistic kitchen visualization for the current cooking step.\n\n");
        prompt.append("Current step:\n").append(describeStep(current)).append("\n\n");

        if (StringUtils.hasText(current.previousExpectedOutput())) {
            prompt.append("Previous step's expected result (for visual continuity - the current step "
                            + "continues from this state, same kitchen and camera setup):\n")
                    .append(current.previousExpectedOutput()).append("\n\n");
        } else {
            prompt.append("This is the first step of the process.\n\n");
        }

        if (current.ingredients().size() > 1) {
            prompt.append("This step involves multiple simultaneous ingredient operations. Compose a single "
                    + "image that clearly shows all of them together — for example a side-by-side or clearly "
                    + "labeled/visually distinguishable composition within the same frame. Do not describe "
                    + "separate images, only one combined scene.\n\n");
        }

        prompt.append("Generate imagePrompt: a single still frame depicting this step and its expected result.");
        return prompt.toString();
    }

    /** At most this many panels in one preparation image; longer flows merge their middle steps. */
    static final int MAX_FLOW_PANELS = 6;

    /**
     * One image for a whole independent preparation flow (e.g. "Marinated Chicken"), attached to the step that makes
     * its output. A single step is one scene; several steps become one image laid out as a storyboard of small
     * panels in cooking order, ending with the finished component.
     */
    public String buildFlowUserPrompt(String outputName, List<RecipeProcessVisualizationInput> steps) {
        String output = StringUtils.hasText(outputName) ? outputName.trim() : "the prepared component";
        StringBuilder prompt = new StringBuilder();
        prompt.append("Describe a photorealistic kitchen visualization of one complete preparation: making ")
                .append(output).append(".\n\n");
        prompt.append("Preparation steps, in order:\n");
        for (int i = 0; i < steps.size(); i++) {
            prompt.append(i + 1).append(". ").append(describeStep(steps.get(i))).append('\n');
        }
        prompt.append('\n');
        if (steps.size() <= 1) {
            prompt.append("Generate imagePrompt: a single still frame showing this preparation and the finished ")
                    .append(output).append(".");
        } else {
            prompt.append(storyboardInstruction(output, steps.size())).append("\n\n");
            prompt.append("Generate imagePrompt: a single image describing that storyboard.");
        }
        return prompt.toString();
    }

    /**
     * Bump whenever the batch system/user prompt wording or structure changes, so a change in image quality can be
     * tied to a prompt change (logged with every batch).
     */
    public static final String BATCH_PROMPT_VERSION = "1";

    /** One image the batch call writes a prompt for, under the short id the model answers with ("m1", "p1"). */
    public record BatchTarget(String id, RecipeProcessVisualizationService.VisualTarget target) {
    }

    /**
     * System prompt for writing every image prompt of a recipe in one call: one shared style guide first, then one
     * self-contained prompt per target, so the images read as one recipe shot in one kitchen.
     */
    public String buildBatchSystemPrompt() {
        return """
                You are a culinary visualization prompt writer. You write the image prompts for every image of one \
                recipe at once, so all images look like one cooking session.

                Return strict JSON only: {"styleGuide": "...", "prompts": [{"id": "...", "imagePrompt": "..."}]}.
                No markdown, prose, or code fences.

                - First write styleGuide: one short paragraph fixing the shared look of every image: the kitchen and \
                countertop, the cookware (name each pan or pot used), the lighting, the camera angle, and the \
                photographic style (photorealistic).
                - Then write exactly one prompt per listed id, using that id.
                - Each imagePrompt is sent to the image model ON ITS OWN: it must be self-contained, so repeat the \
                relevant parts of the style guide (same kitchen, same cookware, same lighting) inside every prompt. \
                Never refer to "the previous image" or to another id.
                - Show each step's action and its expected result; keep quantities and ingredients faithful to the step.
                - Keep the same cookware for the same pan across MAIN steps: the food in it changes, the pan does not.
                - No text, labels, captions or watermarks in any image.
                """;
    }

    /**
     * The whole recipe, in cooking order: MAIN steps as {@code m<i>} (each with its previous step's result for
     * continuity) and Preparation flows as {@code p<i>} (one storyboard image per flow, as in
     * {@link #buildFlowUserPrompt}). {@code styleReferences} are prompts this recipe already has, which new prompts
     * must match.
     */
    public String buildBatchUserPrompt(List<BatchTarget> targets, List<String> styleReferences) {
        StringBuilder prompt = new StringBuilder();
        List<BatchTarget> flows = targets.stream().filter(target -> target.target().flow() != null).toList();
        List<BatchTarget> steps = targets.stream().filter(target -> target.target().flow() == null).toList();

        if (!flows.isEmpty()) {
            prompt.append("PREPARATIONS (one image each, made before or alongside cooking):\n");
            for (BatchTarget target : flows) {
                RecipeProcessVisualizationService.FlowContext flow = target.target().flow();
                String output = StringUtils.hasText(flow.outputName()) ? flow.outputName().trim() : "the prepared component";
                prompt.append(target.id()).append(": making ").append(output).append('\n');
                for (int i = 0; i < flow.steps().size(); i++) {
                    prompt.append("  ").append(i + 1).append(". ").append(describeStep(flow.steps().get(i))).append('\n');
                }
                prompt.append("  Image: ").append(flow.steps().size() <= 1
                        ? "a single still frame showing this preparation and the finished " + output + "."
                        : storyboardInstruction(output, flow.steps().size())).append('\n');
            }
            prompt.append('\n');
        }

        if (!steps.isEmpty()) {
            prompt.append("MAIN COOKING STEPS (one image each, a single still frame of the step and its result):\n");
            for (BatchTarget target : steps) {
                RecipeProcessVisualizationInput step = target.target().step();
                prompt.append(target.id()).append(": ").append(describeStep(step)).append('\n');
                if (StringUtils.hasText(step.previousExpectedOutput())) {
                    prompt.append("  Continues from: ").append(step.previousExpectedOutput()).append('\n');
                }
                if (step.ingredients().size() > 1) {
                    prompt.append("  Several ingredients at once: show them together in one combined scene.\n");
                }
            }
            prompt.append('\n');
        }

        if (styleReferences != null && !styleReferences.isEmpty()) {
            prompt.append("EXISTING PROMPTS OF THIS RECIPE (match their kitchen, cookware, lighting and style exactly):\n");
            styleReferences.forEach(reference -> prompt.append("- ").append(reference).append('\n'));
            prompt.append('\n');
        }

        prompt.append("Return the styleGuide and one prompt for each of these ids: ")
                .append(targets.stream().map(BatchTarget::id).collect(java.util.stream.Collectors.joining(", ")))
                .append('.');
        return prompt.toString();
    }

    /** The storyboard layout for a multi-step preparation flow's single image. */
    private static String storyboardInstruction(String output, int stepCount) {
        int panels = Math.min(stepCount, MAX_FLOW_PANELS) + 1;
        StringBuilder instruction = new StringBuilder("Compose ONE image laid out as a storyboard of ").append(panels)
                .append(" small panels in a clean grid, read left to right and top to bottom: one panel per step in ")
                .append("the order above");
        if (stepCount > MAX_FLOW_PANELS) instruction.append(" (merge consecutive minor steps to fit)");
        instruction.append(", and a final panel showing the finished ").append(output)
                .append(". Keep the same kitchen, lighting and ingredients across panels; no text or labels.");
        return instruction.toString();
    }

    private String describeStep(RecipeProcessVisualizationInput step) {
        StringBuilder description = new StringBuilder();
        description.append("action=").append(orNone(step.action()));

        if (!step.ingredients().isEmpty()) {
            description.append(", actionOn.ingredients=[");
            for (int i = 0; i < step.ingredients().size(); i++) {
                RecipeProcessVisualizationInput.IngredientTarget ingredient = step.ingredients().get(i);
                if (i > 0) description.append("; ");
                description.append(ingredient.name());
                if (StringUtils.hasText(ingredient.quantity())) {
                    description.append(" (").append(ingredient.quantity());
                    if (StringUtils.hasText(ingredient.unit())) description.append(' ').append(ingredient.unit());
                    description.append(')');
                }
                if (StringUtils.hasText(ingredient.preparationStyle())) {
                    description.append(" - ").append(ingredient.preparationStyle());
                }
            }
            description.append(']');
        }

        if (!step.subprocessNames().isEmpty()) {
            description.append(", actionOn.subprocesses=").append(String.join(", ", step.subprocessNames()));
        }

        description.append(", actionDescription=").append(orNone(step.actionDescription()));
        description.append(", expectedOutput=").append(orNone(step.expectedOutput()));

        List<String> advanced = advancedProperties(step);
        if (!advanced.isEmpty()) {
            description.append(", advanced=").append(String.join(", ", advanced));
        }

        return description.toString();
    }

    private List<String> advancedProperties(RecipeProcessVisualizationInput step) {
        return java.util.stream.Stream.of(
                        StringUtils.hasText(step.temperature()) ? "temperature=" + step.temperature() : null,
                        StringUtils.hasText(step.flameLevel()) ? "flame=" + step.flameLevel() : null,
                        StringUtils.hasText(step.duration()) ? "duration=" + step.duration() : null)
                .filter(StringUtils::hasText)
                .toList();
    }

    private String orNone(String value) {
        return StringUtils.hasText(value) ? value : "(none)";
    }
}
