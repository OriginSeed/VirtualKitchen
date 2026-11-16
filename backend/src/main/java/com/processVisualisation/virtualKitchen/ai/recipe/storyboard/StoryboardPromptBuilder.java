package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.RecipeStoryboard;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationImageProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The prompts of the storyboard's two text calls (planning the panels, then writing the one image prompt from them)
 * and the fixed-template image prompt used when the second call does not deliver one.
 */
@Component
public class StoryboardPromptBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Bumped when the planning rules change, so stored plans can be told apart. */
    public static final String PROMPT_VERSION = "storyboard-v1";

    // ------------------------------------------------------------------------------------------------- stage 1

    public String buildPlanSystemPrompt(int maxPanels) {
        return """
                You plan a visual storyboard that explains a whole recipe as a sequence of panels (photos).
                You receive the recipe as JSON: "preparation.flows" (each flow makes one prepared component, named by \
                "output") and "main.nodes" (the main cooking sequence). A node is a step, or a check when it has \
                "kind":"CONDITION". "next" (and a check's "yes") lead forward; a check's "no" loops back to repeat a step. \
                "uses" on a main step names the preparation step whose output it brings in.

                THE RULE: one panel = one visually meaningful cooking action or preparation outcome. The number of panels \
                has no fixed relation to the number of nodes.

                GROUPING
                - Merge several small steps when together they make one outcome a viewer would see as one action \
                (adding curd, spices and ginger-garlic paste and mixing them is one panel: "Prepare marinade").
                - Keep visually distinct actions in separate panels (cooking rice and draining it are two panels).
                - Never merge steps only because they are next to each other; never merge steps of different \
                preparation flows; never mix preparation steps and main steps in one panel.
                - Waiting, resting, timing and checks are not panels of their own: put them in the panel of the action \
                they belong to, or list a step under "omittedSteps" with a reason when it shows nothing new.
                - Lose no cooking transformation: everything that changes how the food looks must be visible in a panel.
                - Use at most %d panels. For a long recipe merge the least important actions; prefer a panel count that \
                fills a grid evenly (4, 6, 8, 9, 12, 15 or 16).

                ORDER
                - Preparation panels come first, the panels of one flow together and in the flow's order; then the main \
                panels in the true cooking order of "main.nodes". Number them with "sequence" 1, 2, 3...

                EVERY PANEL
                - "panelId": "P1", "P2"... ; "group": "PREPARATION" or "MAIN"; "component": the prepared component a \
                preparation panel works towards.
                - "description": a short title of the action ("Marinate chicken").
                - "sourceStepIds": the ids of ALL nodes this panel stands for, copied exactly. Every step id of the \
                recipe must appear in some panel's sourceStepIds or in omittedSteps.
                - "imagePrompt": two or three sentences describing exactly what the photo shows: the food and its \
                state (raw, chopped, marinated, golden, simmering), the vessel or surface, and the action being done, \
                with important quantities. It must be understandable without the other panels: name things, never write \
                "as before" or "the previous panel". Invent no ingredient, tool or action the recipe does not have.
                - "dependsOn": panelIds of earlier panels that must have happened for this one to be possible.
                - "referencePanels": panelIds of earlier panels whose result is visible in this panel (the panel that \
                made the marinated chicken, for the panel that adds it to the pan).
                - "ingredients" and "equipment": what is visible in the panel.
                - "expectedState": how the food looks when the panel's action is done.

                Also return "title" (the dish) and "dishContext" (one line: what dish this is and its cuisine or style).
                Return ONE JSON object and nothing else.
                """.formatted(maxPanels);
    }

    public String buildPlanUserPrompt(StoryboardSource source) {
        return "RECIPE\n" + source.json() + "\n\nStep ids that must each be in a panel or in omittedSteps: "
                + String.join(", ", source.stepAliases()) + ".";
    }

    public String buildPlanRetryPrompt(StoryboardSource source, String previousOutput, List<String> problems) {
        StringBuilder prompt = new StringBuilder(buildPlanUserPrompt(source));
        prompt.append("\n\nYour previous answer was not usable:\n");
        problems.stream().limit(20).forEach(problem -> prompt.append("- ").append(problem).append('\n'));
        if (StringUtils.hasText(previousOutput)) {
            prompt.append("\nPrevious answer:\n").append(abbreviate(previousOutput, 6000)).append('\n');
        }
        prompt.append("\nReturn the complete corrected JSON object.");
        return prompt.toString();
    }

    // ------------------------------------------------------------------------------------------------- stage 2

    public String buildMasterSystemPrompt() {
        return """
                You write ONE prompt for an image model. The image is a storyboard: a single picture made of several \
                panels in a grid, each panel a photo of one cooking action.
                You receive the storyboard specification as JSON: the grid, the shared photographic style and the panels \
                in order. Translate it faithfully: do not reinterpret the recipe, do not add, drop, merge, split or \
                reorder panels, and do not invent ingredients, tools or actions.

                The prompt you write must contain, in this order:
                1. One opening paragraph: a single image divided into the given grid of equal panels (state rows and \
                columns and the number of panels), separated by thin plain white gutters that run straight across the \
                whole image, read left to right and top to bottom. If the grid has more cells than panels, the cells \
                after the last panel stay plain white and empty.
                2. One line per panel, in sequence, each starting with "Panel N (row R, column C):" using the given \
                position, followed by what that panel shows, written from the panel's "imagePrompt" and \
                "expectedState". Keep quantities and food states. Each line must make sense on its own.
                3. A consistency paragraph: every panel uses the same camera angle, lighting and photographic style \
                (use the given style), realistic ingredients and cookware, and natural hands where an action needs \
                them. A prepared component looks the same in the panel that makes it and in every panel that uses it \
                (see "referencePanels"): same colour, cut and texture. Raw food stays raw until a panel cooks it.
                4. A restrictions paragraph: no text, numbers, letters, labels, captions, arrows, borders or \
                decoration anywhere; nothing crosses from one panel into another; every panel is clearly different \
                from its neighbours; clear cooking actions matter more than artistic effect.

                Write plain prose for an image model, no markdown. Return ONE JSON object: {"masterPrompt": "..."}.
                """;
    }

    public String buildMasterUserPrompt(RecipeStoryboard storyboard, StoryboardLayout layout,
                                        VisualizationImageProperties.Scene style) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("dish", orBlank(storyboard.getTitle()));
        if (StringUtils.hasText(storyboard.getDishContext())) root.put("dishContext", storyboard.getDishContext());
        Map<String, Object> grid = new LinkedHashMap<>();
        grid.put("rows", layout.rows());
        grid.put("columns", layout.columns());
        grid.put("panels", storyboard.getPanels().size());
        root.put("grid", grid);
        Map<String, Object> look = new LinkedHashMap<>();
        look.put("camera", cameraOf(style));
        look.put("lighting", style.getLighting());
        look.put("style", style.getStyle());
        look.put("setting", style.getEnvironment());
        root.put("style", look);
        List<Map<String, Object>> panels = new ArrayList<>();
        for (RecipeStoryboard.Panel panel : storyboard.getPanels()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("panel", panel.getSequence());
            entry.put("panelId", panel.getPanelId());
            entry.put("position", layout.position(panel.getSequence()));
            entry.put("group", panel.getGroup());
            entry.put("description", panel.getDescription());
            entry.put("imagePrompt", panel.getImagePrompt());
            if (StringUtils.hasText(panel.getExpectedState())) entry.put("expectedState", panel.getExpectedState());
            if (!panel.getReferencePanels().isEmpty()) entry.put("referencePanels", panel.getReferencePanels());
            if (!panel.getIngredients().isEmpty()) entry.put("ingredients", panel.getIngredients());
            if (!panel.getEquipment().isEmpty()) entry.put("equipment", panel.getEquipment());
            panels.add(entry);
        }
        root.put("panels", panels);
        try {
            return "STORYBOARD SPECIFICATION\n" + MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the storyboard specification", e);
        }
    }

    public String buildMasterRetryPrompt(String userPrompt, List<String> problems) {
        return userPrompt + "\n\nYour previous prompt was not usable: " + String.join("; ", problems)
                + ". Write it again with one \"Panel N (row R, column C):\" line for every panel.";
    }

    /** Problems of a written master prompt: empty when it names the grid's every panel. */
    public List<String> checkMasterPrompt(String masterPrompt, int panels) {
        List<String> problems = new ArrayList<>();
        if (!StringUtils.hasText(masterPrompt)) {
            problems.add("the prompt is empty");
            return problems;
        }
        String lower = masterPrompt.toLowerCase();
        List<String> missing = new ArrayList<>();
        for (int panel = 1; panel <= panels; panel++) {
            if (!java.util.regex.Pattern.compile("panel\\s*" + panel + "(?!\\d)").matcher(lower).find()) {
                missing.add(String.valueOf(panel));
            }
        }
        if (!missing.isEmpty()) problems.add("no line for panel " + String.join(", ", missing));
        if (java.util.regex.Pattern.compile("panel\\s*" + (panels + 1) + "(?!\\d)").matcher(lower).find()) {
            problems.add("it describes more than " + panels + " panels");
        }
        return problems;
    }

    /**
     * The image prompt assembled from the specification by a fixed template. Used when the text model does not deliver
     * a usable one, so a storyboard is never lost to the second call.
     */
    public String buildTemplateMasterPrompt(RecipeStoryboard storyboard, StoryboardLayout layout,
                                            VisualizationImageProperties.Scene style) {
        int count = storyboard.getPanels().size();
        StringBuilder text = new StringBuilder();
        text.append("One image divided into a grid of ").append(layout.rows()).append(" rows and ")
                .append(layout.columns()).append(" columns of equal panels, ").append(count)
                .append(count == 1 ? " panel" : " panels").append(" in total, separated by thin plain white gutters ")
                .append("that run straight across the whole image. Panels are read left to right, top to bottom, in ")
                .append("cooking order. Each panel is a photo of one cooking action");
        if (StringUtils.hasText(storyboard.getTitle())) text.append(" of making ").append(storyboard.getTitle().trim());
        text.append('.');
        if (layout.cells() > count) {
            text.append(" The ").append(layout.cells() - count).append(layout.cells() - count == 1 ? " cell" : " cells")
                    .append(" after the last panel ").append(layout.cells() - count == 1 ? "stays" : "stay")
                    .append(" plain white and empty.");
        }
        text.append('\n');
        for (RecipeStoryboard.Panel panel : storyboard.getPanels()) {
            text.append("Panel ").append(panel.getSequence()).append(" (").append(layout.position(panel.getSequence()))
                    .append("): ").append(sentence(panel.getImagePrompt()));
            if (StringUtils.hasText(panel.getExpectedState())) {
                text.append(" Result: ").append(sentence(panel.getExpectedState()));
            }
            text.append('\n');
        }
        text.append("Every panel uses the same camera (").append(cameraOf(style)).append("), ")
                .append(style.getLighting()).append(", ").append(style.getStyle()).append(" style and ")
                .append(style.getEnvironment()).append(", with realistic ingredients, cookware and natural hands where an ")
                .append("action needs them. A prepared component looks the same in the panel that makes it and in every ")
                .append("panel that uses it. Raw food stays raw until a panel cooks it.\n");
        text.append("No text, numbers, letters, labels, captions, arrows, borders or decoration anywhere. Nothing crosses ")
                .append("from one panel into another. Every panel is clearly different from its neighbours.");
        if (StringUtils.hasText(storyboard.getDishContext())) {
            text.append("\nContext: ").append(storyboard.getDishContext().trim());
        }
        return text.toString();
    }

    private static String cameraOf(VisualizationImageProperties.Scene style) {
        return "about " + style.getCameraAngle() + " degrees above the counter looking down";
    }

    private static String sentence(String text) {
        String trimmed = orBlank(text).trim();
        if (trimmed.isEmpty()) return trimmed;
        char last = trimmed.charAt(trimmed.length() - 1);
        return last == '.' || last == '!' || last == '?' ? trimmed : trimmed + ".";
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String orBlank(String value) {
        return value == null ? "" : value;
    }
}
