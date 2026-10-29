package com.processVisualisation.virtualKitchen.ai.platform.evaluation;

import java.util.Arrays;
import java.util.List;

/**
 * The rules a human evaluation is scored by. The single source for weights and tags: the admin UI reads them from
 * {@code GET /rubric}, and every stored score records the {@link #VERSION} it was computed with, so a later change of
 * rules never silently mixes with scores made under the old ones.
 */
public final class EvaluationRubric {

    public static final String VERSION = "v1";

    /** A recipe's score blends its mean image score with its worst one: one broken step ruins the recipe. */
    public static final double MEAN_WEIGHT = 0.7;
    public static final double WORST_WEIGHT = 0.3;

    /** An image whose step correctness is at or below this fails the whole recipe. */
    public static final int HARD_FAIL_STEP_CORRECTNESS = 2;

    /** Below this many scored recipe sets a model's quality figures are flagged as a low sample. */
    public static final int LOW_SAMPLE_SETS = 10;

    private EvaluationRubric() {
    }

    /** A rated dimension of one image; {@link #DEFECTS} is derived from the defect tags, never rated directly. */
    public enum Dimension {
        CONSISTENCY("Consistency", "Same cookware, counter, lighting and camera as the rest of the recipe", 35),
        STEP_CORRECTNESS("Step correctness", "Shows this step's action, ingredients and tools", 30),
        STATE_CONTINUITY("State continuity", "Earlier changes persist (browned onions stay browned)", 15),
        DEFECTS("Defects", "Derived from the defect tags", 15),
        REALISM("Realism", "Looks like real, appetizing food", 5);

        private final String label;
        private final String description;
        private final int weight;

        Dimension(String label, String description, int weight) {
            this.label = label;
            this.description = description;
            this.weight = weight;
        }

        public int weight() {
            return weight;
        }
    }

    public enum DefectTag {
        ODD_CROP("Odd crop", false),
        DUPLICATE_OBJECTS("Duplicate objects", false),
        LIGHTING_SHIFT("Lighting shift", false),
        WRONG_COOKWARE("Wrong cookware", true),
        MISSING_INGREDIENT("Missing ingredient", true),
        WRONG_STATE("Wrong ingredient state", true),
        MALFORMED_HANDS("Malformed hands", true),
        GARBLED_TEXT("Garbled text", true),
        MELTED_OBJECTS("Melted objects", true);

        private final String label;
        private final boolean major;

        DefectTag(String label, boolean major) {
            this.label = label;
            this.major = major;
        }

        public boolean major() {
            return major;
        }
    }

    public enum ReasonTag {
        INCONSISTENT_SCENE("Inconsistent scene"),
        WRONG_ACTION("Wrong action"),
        STATE_LOST("State lost"),
        DEFECTS("Defects"),
        UNAPPETIZING("Unappetizing"),
        OTHER("Other");

        private final String label;

        ReasonTag(String label) {
            this.label = label;
        }
    }

    public record Option(String id, String label, String description, Integer weight, Boolean major) {
    }

    /** The rubric as the admin UI shows it. */
    public record View(String version, List<Option> dimensions, List<Option> defectTags, List<Option> reasonTags,
                       double meanWeight, double worstWeight, int hardFailStepCorrectness, int lowSampleSets) {
    }

    public static View view() {
        return new View(VERSION,
                Arrays.stream(Dimension.values())
                        .map(d -> new Option(d.name(), d.label, d.description, d.weight, null)).toList(),
                Arrays.stream(DefectTag.values()).map(t -> new Option(t.name(), t.label, null, null, t.major)).toList(),
                Arrays.stream(ReasonTag.values()).map(t -> new Option(t.name(), t.label, null, null, null)).toList(),
                MEAN_WEIGHT, WORST_WEIGHT, HARD_FAIL_STEP_CORRECTNESS, LOW_SAMPLE_SETS);
    }
}
