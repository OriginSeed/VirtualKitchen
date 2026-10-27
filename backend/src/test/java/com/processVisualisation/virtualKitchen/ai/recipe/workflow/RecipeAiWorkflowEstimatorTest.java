package com.processVisualisation.virtualKitchen.ai.recipe.workflow;

import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelRegistry;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelTier;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.dto.RecipeAiWorkflowEstimateDTO;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType.NARRATION;
import static com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType.PROCESS;
import static com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType.VISUALS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecipeAiWorkflowEstimatorTest {

    private AiModelRegistry registry;
    private RecipeAiWorkflowEstimator estimator;

    @BeforeEach
    void setUp() {
        registry = mock(AiModelRegistry.class);
        when(registry.defaultFor(AiCapability.TEXT_TO_TEXT)).thenReturn(model(ModelTier.PAID, 1));
        when(registry.defaultFor(AiCapability.TEXT_TO_IMAGE)).thenReturn(model(ModelTier.PAID, 5));
        when(registry.defaultFor(AiCapability.TEXT_TO_SPEECH)).thenReturn(model(ModelTier.PAID, 1));
        estimator = new RecipeAiWorkflowEstimator(registry, new RecipeAiWorkflowProperties());
    }

    @Test
    void completeExperienceBeforeTheProcessExistsUsesTheAssumedStepRange() {
        RecipeAiWorkflowEstimateDTO estimate = estimator.estimate(List.of(PROCESS, VISUALS, NARRATION), estimator.assumedStepCounts());

        assertEquals(List.of(PROCESS, VISUALS, NARRATION), estimate.getTasks().stream().map(RecipeAiWorkflowEstimateDTO.TaskEstimate::getTask).toList());
        assertRange(estimate, PROCESS, 1, 2);       // 1–2 text requests
        assertRange(estimate, VISUALS, 36, 106);    // 1 batched prompt request + 6–20 steps × 5 image + 5 scene image
        assertRange(estimate, NARRATION, 6, 20);    // 6–20 steps × 1
        assertEquals(43, estimate.getMinCredits());
        assertEquals(128, estimate.getMaxCredits());
        assertTrue(estimate.isApproximate());
        assertFalse(estimate.isStepCountKnown());
    }

    @Test
    void onlySelectedTasksArePriced() {
        RecipeAiWorkflowEstimateDTO estimate = estimator.estimate(List.of(PROCESS), estimator.assumedStepCounts());

        assertEquals(1, estimate.getTasks().size());
        assertEquals(1, estimate.getMinCredits());
        assertEquals(2, estimate.getMaxCredits());
    }

    @Test
    void existingProcessIsPricedOnTheStepsStillMissingWork() {
        RecipeAiWorkflowEstimateDTO estimate = estimator.estimate(List.of(VISUALS, NARRATION),
                RecipeAiWorkflowEstimator.StepCounts.exact(3, 4));

        assertRange(estimate, VISUALS, 16, 16);     // 1 prompt request + 3 × 5 image
        assertRange(estimate, NARRATION, 4, 4);
        assertTrue(estimate.isStepCountKnown());
    }

    @Test
    void moreImagesThanOnePromptRequestHoldsNeedAnotherRequest() {
        RecipeAiWorkflowEstimateDTO estimate = estimator.estimate(List.of(VISUALS), RecipeAiWorkflowEstimator.StepCounts.exact(25, 0));

        assertRange(estimate, VISUALS, 127, 127);   // 2 prompt requests (24 + 1) + 25 × 5 image
    }

    @Test
    void aRecipeWithoutASceneImagePaysForItOnce() {
        RecipeAiWorkflowEstimateDTO estimate = estimator.estimate(List.of(VISUALS), RecipeAiWorkflowEstimator.StepCounts.exact(3, 0, true));

        assertRange(estimate, VISUALS, 21, 21);     // 1 prompt request + 3 × 5 image + 5 scene image
    }

    @Test
    void nothingMissingCostsNoPromptRequest() {
        RecipeAiWorkflowEstimateDTO estimate = estimator.estimate(List.of(VISUALS), RecipeAiWorkflowEstimator.StepCounts.exact(0, 0));

        assertRange(estimate, VISUALS, 0, 0);
    }

    @Test
    void openSourceModelsCostNothing() {
        when(registry.defaultFor(AiCapability.TEXT_TO_IMAGE)).thenReturn(model(ModelTier.OPEN_SOURCE, 5));
        when(registry.defaultFor(AiCapability.TEXT_TO_SPEECH)).thenThrow(new IllegalStateException("not configured"));

        RecipeAiWorkflowEstimateDTO estimate = estimator.estimate(List.of(VISUALS, NARRATION), RecipeAiWorkflowEstimator.StepCounts.exact(3, 3));

        assertRange(estimate, VISUALS, 1, 1); // only the one batched 1-credit prompt request
        assertRange(estimate, NARRATION, 0, 0);
    }

    private static void assertRange(RecipeAiWorkflowEstimateDTO estimate, RecipeAiTaskType task, int min, int max) {
        RecipeAiWorkflowEstimateDTO.TaskEstimate found = estimate.getTasks().stream()
                .filter(candidate -> candidate.getTask() == task).findFirst().orElseThrow();
        assertEquals(min, found.getMinCredits(), task + " min");
        assertEquals(max, found.getMaxCredits(), task + " max");
    }

    private static ModelDefinition model(ModelTier tier, int creditCost) {
        ModelDefinition model = new ModelDefinition();
        model.setTier(tier);
        model.setCreditCost(creditCost);
        return model;
    }
}
