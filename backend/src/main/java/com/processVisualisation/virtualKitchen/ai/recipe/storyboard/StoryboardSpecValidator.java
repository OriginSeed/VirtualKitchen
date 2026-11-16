package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.RecipeStoryboard;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a {@link StoryboardPlan} against the recipe it was planned from and turns it into the panels that are
 * stored (real node ids in place of the planner's aliases).
 * <ul>
 *   <li><b>Errors</b> make the plan unusable: no panels or too many, a panel without source steps, prompt or
 *       description, an unknown node id, a step that is in no panel and not listed as omitted, a panel that mixes
 *       preparation and main steps, preparation panels after main panels, main panels out of cooking order.</li>
 *   <li><b>Link problems</b> are a {@code dependsOn} or {@code referencePanels} entry naming an unknown panel, the
 *       panel itself, or a later panel (which also rules out cycles). They are worth one more try, and can be repaired
 *       by dropping the entry.</li>
 * </ul>
 * A main panel that uses a prepared component always references and depends on the panel that makes it; a missing
 * link of that kind is added without a complaint.
 */
public final class StoryboardSpecValidator {

    private StoryboardSpecValidator() {
    }

    /**
     * @param errors problems that make the plan unusable
     * @param linkProblems invalid panel links, already dropped from {@code panels}
     * @param panels the checked panels in sequence order, with real node ids (meaningful only without errors)
     * @param omitted the steps listed as omitted, with real node ids
     */
    public record Result(List<String> errors, List<String> linkProblems, List<RecipeStoryboard.Panel> panels,
                         List<RecipeStoryboard.OmittedStep> omitted) {

        public boolean usable() {
            return errors.isEmpty();
        }

        public boolean clean() {
            return errors.isEmpty() && linkProblems.isEmpty();
        }

        /** Everything a retry should be told about. */
        public List<String> allProblems() {
            List<String> all = new ArrayList<>(errors);
            all.addAll(linkProblems);
            return all;
        }
    }

    public static Result validate(StoryboardPlan plan, StoryboardSource source, int maxPanels) {
        List<String> errors = new ArrayList<>();
        List<String> linkProblems = new ArrayList<>();
        List<StoryboardPlan.Panel> planned = plan == null || plan.panels() == null ? List.of()
                : plan.panels().stream().filter(panel -> panel != null).toList();
        if (planned.isEmpty()) {
            errors.add("The storyboard has no panels");
            return new Result(errors, linkProblems, List.of(), List.of());
        }
        if (planned.size() > maxPanels) {
            errors.add("The storyboard has " + planned.size() + " panels; use at most " + maxPanels
                    + " by merging actions that make one outcome");
        }

        // Panels in their stated sequence (list order where none is stated), renumbered 1..n.
        List<StoryboardPlan.Panel> ordered = new ArrayList<>(planned);
        Map<StoryboardPlan.Panel, Integer> listIndex = new HashMap<>();
        for (int i = 0; i < planned.size(); i++) listIndex.put(planned.get(i), i);
        ordered.sort(Comparator.comparingInt((StoryboardPlan.Panel panel) ->
                panel.sequence() == null ? Integer.MAX_VALUE : panel.sequence()).thenComparingInt(listIndex::get));

        Map<String, Integer> sequenceById = new HashMap<>();
        List<RecipeStoryboard.Panel> panels = new ArrayList<>();
        Map<String, String> panelOfStep = new HashMap<>();
        Set<String> covered = new HashSet<>();
        boolean mainSeen = false;
        int lastMainOrder = -1;
        for (int i = 0; i < ordered.size(); i++) {
            StoryboardPlan.Panel source0 = ordered.get(i);
            int sequence = i + 1;
            String panelId = StringUtils.hasText(source0.panelId()) ? source0.panelId().trim() : "P" + sequence;
            String label = "Panel " + panelId;
            if (sequenceById.putIfAbsent(panelId, sequence) != null) {
                errors.add(label + " appears more than once; every panelId must be unique");
            }
            if (!StringUtils.hasText(source0.description())) errors.add(label + " has no description");
            if (!StringUtils.hasText(source0.imagePrompt())) errors.add(label + " has no imagePrompt");

            List<String> aliases = distinct(source0.sourceStepIds());
            if (aliases.isEmpty()) errors.add(label + " has no sourceStepIds");
            List<String> nodeIds = new ArrayList<>();
            Set<String> groups = new LinkedHashSet<>();
            int firstOrder = Integer.MAX_VALUE;
            for (String alias : aliases) {
                StoryboardSource.Node node = source.nodes().get(alias);
                if (node == null) {
                    errors.add(label + " names an unknown step id \"" + alias + "\"");
                    continue;
                }
                nodeIds.add(node.nodeId());
                groups.add(node.group());
                if (node.step()) {
                    covered.add(alias);
                    panelOfStep.putIfAbsent(alias, panelId);
                    firstOrder = Math.min(firstOrder, node.order());
                }
            }
            String group = StoryboardSource.MAIN.equalsIgnoreCase(text(source0.group())) ? StoryboardSource.MAIN
                    : StoryboardSource.PREPARATION.equalsIgnoreCase(text(source0.group())) ? StoryboardSource.PREPARATION
                    : groups.size() == 1 ? groups.iterator().next() : null;
            if (groups.size() > 1) {
                errors.add(label + " mixes preparation steps and main cooking steps; split it");
            } else if (groups.size() == 1 && group != null && !groups.contains(group)) {
                group = groups.iterator().next();
            }
            if (group == null) group = StoryboardSource.MAIN;
            if (StoryboardSource.MAIN.equals(group)) {
                mainSeen = true;
                if (firstOrder != Integer.MAX_VALUE) {
                    if (firstOrder < lastMainOrder) {
                        errors.add(label + " is out of cooking order: main panels must follow the main process order");
                    }
                    lastMainOrder = Math.max(lastMainOrder, firstOrder);
                }
            } else if (mainSeen) {
                errors.add(label + " is a preparation panel placed after main cooking panels; preparation comes first");
            }

            RecipeStoryboard.Panel panel = new RecipeStoryboard.Panel();
            panel.setPanelId(panelId);
            panel.setSequence(sequence);
            panel.setGroup(group);
            panel.setComponent(text(source0.component()));
            panel.setDescription(text(source0.description()));
            panel.setSourceStepIds(nodeIds);
            panel.setImagePrompt(text(source0.imagePrompt()));
            panel.setDependsOn(distinct(source0.dependsOn()));
            panel.setReferencePanels(distinct(source0.referencePanels()));
            panel.setIngredients(distinct(source0.ingredients()));
            panel.setEquipment(distinct(source0.equipment()));
            panel.setExpectedState(text(source0.expectedState()));
            panels.add(panel);
        }

        // Every step is told by a panel, or deliberately left out.
        List<RecipeStoryboard.OmittedStep> omitted = new ArrayList<>();
        Set<String> omittedAliases = new HashSet<>();
        for (StoryboardPlan.Omitted entry : plan.omittedSteps() == null ? List.<StoryboardPlan.Omitted>of() : plan.omittedSteps()) {
            if (entry == null || !StringUtils.hasText(entry.stepId())) continue;
            StoryboardSource.Node node = source.nodes().get(entry.stepId().trim());
            if (node == null) {
                errors.add("omittedSteps names an unknown step id \"" + entry.stepId().trim() + "\"");
                continue;
            }
            if (!omittedAliases.add(node.alias()) || covered.contains(node.alias())) continue;
            RecipeStoryboard.OmittedStep step = new RecipeStoryboard.OmittedStep();
            step.setStepId(node.nodeId());
            step.setReason(text(entry.reason()));
            omitted.add(step);
        }
        List<String> missing = source.stepAliases().stream()
                .filter(alias -> !covered.contains(alias) && !omittedAliases.contains(alias)).toList();
        if (!missing.isEmpty()) {
            errors.add("These steps are in no panel and not in omittedSteps: " + String.join(", ", missing));
        }

        // Links point back to earlier panels only.
        for (RecipeStoryboard.Panel panel : panels) {
            panel.setDependsOn(checkedLinks(panel, panel.getDependsOn(), "dependsOn", sequenceById, linkProblems));
            panel.setReferencePanels(checkedLinks(panel, panel.getReferencePanels(), "referencePanels", sequenceById, linkProblems));
        }
        // A panel that brings in a prepared component shows it as the panel that made it did.
        for (int i = 0; i < ordered.size(); i++) {
            RecipeStoryboard.Panel panel = panels.get(i);
            for (String alias : distinct(ordered.get(i).sourceStepIds())) {
                StoryboardSource.Node node = source.nodes().get(alias);
                if (node == null) continue;
                for (String used : node.uses()) {
                    String maker = panelOfStep.get(used);
                    Integer makerSequence = maker == null ? null : sequenceById.get(maker);
                    if (makerSequence == null || makerSequence >= panel.getSequence()) continue;
                    if (!panel.getReferencePanels().contains(maker)) panel.getReferencePanels().add(maker);
                    if (!panel.getDependsOn().contains(maker)) panel.getDependsOn().add(maker);
                }
            }
        }
        return new Result(errors, linkProblems, panels, omitted);
    }

    private static List<String> checkedLinks(RecipeStoryboard.Panel panel, List<String> links, String field,
                                             Map<String, Integer> sequenceById, List<String> problems) {
        List<String> kept = new ArrayList<>();
        for (String link : links) {
            Integer sequence = sequenceById.get(link);
            if (sequence == null) {
                problems.add("Panel " + panel.getPanelId() + " " + field + " names an unknown panel \"" + link + "\"");
            } else if (sequence >= panel.getSequence()) {
                problems.add("Panel " + panel.getPanelId() + " " + field + " names \"" + link
                        + "\", which is not an earlier panel");
            } else {
                kept.add(link);
            }
        }
        return kept;
    }

    private static List<String> distinct(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values == null) return result;
        for (String value : values) {
            if (!StringUtils.hasText(value)) continue;
            String trimmed = value.trim();
            if (!result.contains(trimmed)) result.add(trimmed);
        }
        return result;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
