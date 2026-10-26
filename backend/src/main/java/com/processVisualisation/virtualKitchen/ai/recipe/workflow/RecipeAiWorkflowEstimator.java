package com.processVisualisation.virtualKitchen.ai.recipe.workflow;

import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelRegistry;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelTier;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationPromptBatchService;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto.RecipeAiWorkflowEstimateDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto.RecipeAiWorkflowEstimateDTO.TaskEstimate;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Prices a task selection before anything runs, from the same model registry the queue charges
 * through: each capability's configured default model and its {@code creditCost} (0 for open-source
 * models, which never reserve credits). Fallbacks to a model with a different price, and validation
 * retries, are why every figure is a range.
 * <ul>
 *   <li>Recipe Process — one text call, plus one corrective retry if validation fails.</li>
 *   <li>Step Visuals — one text call writing the image prompts of the whole recipe (one per
 *       {@link VisualizationPromptBatchService#MAX_TARGETS_PER_REQUEST} images), plus one image call per image.</li>
 *   <li>Voice Narration — per step, one TTS call (the script itself is built without AI).</li>
 * </ul>
 */
@Component
public class RecipeAiWorkflowEstimator {

    /** First attempt plus the one corrective retry {@code RecipeProcessGenerationService} may make. */
    static final int MAX_PROCESS_ATTEMPTS = 2;

    private final AiModelRegistry modelRegistry;
    private final RecipeAiWorkflowProperties properties;

    public RecipeAiWorkflowEstimator(AiModelRegistry modelRegistry, RecipeAiWorkflowProperties properties) {
        this.modelRegistry = modelRegistry;
        this.properties = properties;
    }

    /**
     * Step counts the downstream tasks will actually pay for. When the process hasn't been generated
     * yet the count is the configured range; otherwise it is the saved steps still missing an image
     * (resp. usable narration), since finished ones are reused without a provider call.
     */
    public record StepCounts(int minVisualSteps, int maxVisualSteps, int minNarrationSteps, int maxNarrationSteps, boolean known) {

        public static StepCounts exact(int visualSteps, int narrationSteps) {
            return new StepCounts(visualSteps, visualSteps, narrationSteps, narrationSteps, true);
        }
    }

    /** The configured step range, for a selection that will generate a new process. */
    public StepCounts assumedStepCounts() {
        int min = Math.max(0, properties.getEstimate().getMinSteps());
        int max = Math.max(min, properties.getEstimate().getMaxSteps());
        return new StepCounts(min, max, min, max, false);
    }

    public RecipeAiWorkflowEstimateDTO estimate(List<RecipeAiTaskType> tasks, StepCounts steps) {
        int textCost = creditCost(AiCapability.TEXT_TO_TEXT);
        List<TaskEstimate> estimates = new ArrayList<>();
        for (RecipeAiTaskType task : tasks) {
            estimates.add(switch (task) {
                case PROCESS -> TaskEstimate.builder()
                        .task(task)
                        .minCredits(textCost)
                        .maxCredits(textCost * MAX_PROCESS_ATTEMPTS)
                        .basis("1–" + MAX_PROCESS_ATTEMPTS + " text requests × " + credits(textCost))
                        .build();
                case VISUALS -> {
                    int imageCost = creditCost(AiCapability.TEXT_TO_IMAGE);
                    int maxPromptRequests = promptRequests(steps.maxVisualSteps());
                    yield TaskEstimate.builder()
                            .task(task)
                            .minCredits(promptRequests(steps.minVisualSteps()) * textCost + steps.minVisualSteps() * imageCost)
                            .maxCredits(maxPromptRequests * textCost + steps.maxVisualSteps() * imageCost)
                            .basis(range(promptRequests(steps.minVisualSteps()), maxPromptRequests) + " × " + credits(textCost)
                                    + " (image prompts) + " + range(steps.minVisualSteps(), steps.maxVisualSteps())
                                    + " × " + credits(imageCost) + " (image)")
                            .build();
                }
                case NARRATION -> {
                    int perStep = creditCost(AiCapability.TEXT_TO_SPEECH);
                    yield TaskEstimate.builder()
                            .task(task)
                            .minCredits(steps.minNarrationSteps() * perStep)
                            .maxCredits(steps.maxNarrationSteps() * perStep)
                            .basis(range(steps.minNarrationSteps(), steps.maxNarrationSteps()) + " × " + credits(perStep))
                            .build();
                }
            });
        }
        return RecipeAiWorkflowEstimateDTO.builder()
                .tasks(estimates)
                .minCredits(estimates.stream().mapToInt(TaskEstimate::getMinCredits).sum())
                .maxCredits(estimates.stream().mapToInt(TaskEstimate::getMaxCredits).sum())
                .approximate(true)
                .stepCountKnown(steps.known())
                .build();
    }

    /** Text calls the prompt batch makes for this many images: one per {@code MAX_TARGETS_PER_REQUEST}, none for none. */
    static int promptRequests(int images) {
        int perRequest = VisualizationPromptBatchService.MAX_TARGETS_PER_REQUEST;
        return images <= 0 ? 0 : (images + perRequest - 1) / perRequest;
    }

    /** Credits one request costs with the capability's default model; 0 for open-source or unconfigured models. */
    int creditCost(AiCapability capability) {
        ModelDefinition model;
        try {
            model = modelRegistry.defaultFor(capability);
        } catch (IllegalStateException e) {
            return 0; // no default configured: the request itself will fail, so there is nothing to price
        }
        return model.getTier() == ModelTier.OPEN_SOURCE ? 0 : Math.max(0, model.getCreditCost());
    }

    private static String range(int min, int max) {
        String count = min == max ? String.valueOf(min) : min + "–" + max;
        return count + (max == 1 ? " step" : " steps");
    }

    private static String credits(int amount) {
        return amount + (amount == 1 ? " credit" : " credits");
    }
}
