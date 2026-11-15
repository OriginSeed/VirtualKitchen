package com.processVisualisation.virtualKitchen.ai.recipe.visualization;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds {@code ai.visualization.image.*}: which image purpose each recipe step action has, and the generation
 * profile (size, inference steps) each purpose uses. The single place step images are classified — see
 * {@link VisualizationImageProfiles}.
 * <p>
 * Purposes come from the action's catalog {@code category} ({@link #purposeByCategory}), overridable per action
 * ({@link #purposeByAction}); both maps ship with defaults that configured entries are merged over. A purpose with
 * no profile, an unknown action and {@code custom} all use {@link #defaultProfile}. Profiles are empty unless
 * configured, in which case every provider keeps its own configured settings.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.visualization.image")
public class VisualizationImageProperties {

    public static final String INGREDIENT_PREPARATION = "ingredient-preparation";
    public static final String INGREDIENT_ADDITION = "ingredient-addition";
    public static final String COOKING_PROCESS = "cooking-process";
    public static final String MIXING = "mixing";
    public static final String WAITING_RESTING = "waiting-resting";
    public static final String SERVING = "serving";

    /** Purpose per step-catalog action category (e.g. {@code cutting -> ingredient-preparation}). */
    private Map<String, String> purposeByCategory = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("cleaning", INGREDIENT_PREPARATION),
            Map.entry("cutting", INGREDIENT_PREPARATION),
            Map.entry("coating", INGREDIENT_PREPARATION),
            Map.entry("dough", INGREDIENT_PREPARATION),
            Map.entry("handling", INGREDIENT_ADDITION),
            Map.entry("heating", COOKING_PROCESS),
            Map.entry("moist-heat", COOKING_PROCESS),
            Map.entry("frying", COOKING_PROCESS),
            Map.entry("dry-heat", COOKING_PROCESS),
            Map.entry("sauce", COOKING_PROCESS),
            Map.entry("mixing", MIXING),
            Map.entry("control", WAITING_RESTING),
            Map.entry("preservation", WAITING_RESTING),
            Map.entry("finishing", SERVING)));

    /** Purpose for single actions whose category doesn't fit them; wins over {@link #purposeByCategory}. */
    private Map<String, String> purposeByAction = new LinkedHashMap<>(Map.of(
            // Flipping happens in the pan, mid-cook, though the catalog files it under handling.
            "flip", COOKING_PROCESS));

    /** Used for actions without a purpose and purposes without a profile, and for any field a profile leaves unset. */
    private Profile defaultProfile = new Profile();

    /** Generation profile per purpose. */
    private Map<String, Profile> profiles = new LinkedHashMap<>();

    /**
     * The Focused Cooking View: close-up step images whose scene (equipment, camera, lighting, style) is written by
     * code from {@link SceneContext} and {@link #scene}, with the framing chosen by the step's action. False restores
     * the previous behaviour exactly: full-kitchen prompts written entirely by the text model, every image generated
     * in parallel against the scene anchor alone. Read for each visuals job; images already made are unchanged.
     */
    private volatile boolean focusedViewEnabled = true;

    /**
     * With the focused view on: draw each main step against the previous image of the same focus as well as the
     * scene anchor, so the food carries over from step to step. Those steps then run one after another instead of in
     * parallel. Only image models that take reference images use it.
     */
    private volatile boolean referenceChainEnabled = true;

    /** The values {@link #preparationStepsPerImage} may take. */
    public static final java.util.List<Integer> STEPS_PER_IMAGE_OPTIONS = java.util.List.of(1, 2, 4);

    /**
     * With the focused view on: how many Preparation steps share one generated image (1, 2 or 4). Several steps are
     * drawn as equal panels of one image, which is then cut into one image per step, so a step costs a fraction of an
     * image. 1 draws every step on its own.
     */
    private volatile int preparationStepsPerImage = 4;

    /**
     * Steps the prompt writer marks as looking the same as the step before them (resting, setting aside, keeping
     * warm) get no image of their own and reuse the previous step's image. Off: every step is drawn.
     */
    private volatile boolean skipLookAlikeSteps = true;

    /** {@link #preparationStepsPerImage}, with any value outside {@link #STEPS_PER_IMAGE_OPTIONS} read as 1. */
    public int effectivePreparationStepsPerImage() {
        return STEPS_PER_IMAGE_OPTIONS.contains(preparationStepsPerImage) ? preparationStepsPerImage : 1;
    }

    /** What a focused image centres on, per purpose ({@link VisualFocus} names); others use {@link #defaultFocus}. */
    private Map<String, String> focusByPurpose = new LinkedHashMap<>(Map.of(
            INGREDIENT_PREPARATION, VisualFocus.CUTTING_BOARD.name(),
            SERVING, VisualFocus.PLATE.name()));

    /**
     * Focus for a whole catalog category whose purpose does not say where it happens; wins over
     * {@link #focusByPurpose}. Coating and dough work is ingredient preparation, but in a bowl, not on the board.
     */
    private Map<String, String> focusByCategory = new LinkedHashMap<>(Map.of(
            "coating", VisualFocus.PREP_BOWL.name(),
            "dough", VisualFocus.PREP_BOWL.name()));

    /** The focus of every purpose without an entry in {@link #focusByPurpose}, and of unknown actions. */
    private String defaultFocus = VisualFocus.COOKING_VESSEL.name();

    /** The parts of a focused image's scene that are the same for every recipe. */
    private Scene scene = new Scene();

    /**
     * {@code ai.visualization.image.scene.*}: the camera, lighting and style of every focused image, and the
     * equipment used when a recipe's {@link SceneContext} doesn't name one.
     */
    @Data
    public static class Scene {

        /** Degrees above the counter the camera looks down from. */
        private int cameraAngle = 50;
        private String environment = "minimal home cooking setup, plain uncluttered background";
        private String lighting = "soft natural light";
        private String style = "photorealistic";

        private String vessel = "a black round frying pan";
        private String stove = "a black gas stove";
        private String surface = "a light grey stone countertop";
        private String cuttingBoard = "a wooden cutting board";
        private String prepBowl = "a plain steel mixing bowl";
        private String servingDish = "a plain white plate";

        /** The equipment used where a recipe names none. */
        public SceneContext equipment() {
            return new SceneContext(vessel, stove, surface, cuttingBoard, prepBowl, servingDish);
        }
    }

    /** Generation settings for one purpose; a null field falls back to {@link #defaultProfile}, then the provider's own. */
    @Data
    public static class Profile {

        private Integer width;
        private Integer height;
        /** Only sent to providers whose API takes a step count (Draw Things); others ignore it. */
        private Integer steps;
    }
}
