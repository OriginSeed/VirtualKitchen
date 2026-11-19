package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import java.util.List;
import java.util.Map;

/**
 * A recipe as the storyboard planner reads it: the whole flow as one compact JSON text in which every node carries a
 * short alias ({@code p1}, {@code m1}, {@code c1}) instead of its real id, and the way back from each alias to the
 * node it stands for.
 *
 * @param dishName the recipe's name
 * @param json the recipe flow sent to the planner
 * @param hash SHA-256 of {@code json}: two recipes that read the same have the same hash
 * @param nodes every node by alias, in the order the JSON lists them (preparation flows first, then the main process)
 */
public record StoryboardSource(String dishName, String json, String hash, Map<String, Node> nodes) {

    public static final String PREPARATION = "PREPARATION";
    public static final String MAIN = "MAIN";

    /**
     * One recipe node behind an alias.
     *
     * @param group {@link #PREPARATION} or {@link #MAIN}
     * @param step true for a STEP, false for a CONDITION (a check)
     * @param order position within its group, in cooking order
     * @param uses aliases of the preparation steps whose output this step brings in
     * @param hasImage whether the step already shows an image
     */
    public record Node(String alias, Long processId, String nodeId, String group, boolean step, int order,
                       List<String> uses, boolean hasImage) {
    }

    /** Aliases of every STEP, in listing order. */
    public List<String> stepAliases() {
        return nodes.values().stream().filter(Node::step).map(Node::alias).toList();
    }

    /** How many steps and checks the recipe has. */
    public int size() {
        return nodes.size();
    }
}
