package com.processVisualisation.virtualKitchen.ai.platform.preference;

import com.processVisualisation.virtualKitchen.ai.platform.credit.CreditReservation;
import com.processVisualisation.virtualKitchen.ai.platform.credit.CreditService;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelProperties;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelRegistry;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelTier;
import com.processVisualisation.virtualKitchen.ai.platform.routing.ModelSelectionOutcome;
import com.processVisualisation.virtualKitchen.ai.platform.routing.ModelSelectionService;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.RecipeAiWorkflowEstimator;
import com.processVisualisation.virtualKitchen.common.concurrent.NamedTask;
import com.processVisualisation.virtualKitchen.common.concurrent.ThreadPoolTaskPool;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.RecipeAiWorkflowProperties;
import com.processVisualisation.virtualKitchen.ai.recipe.workflow.model.RecipeAiTaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Users' own model choices: which model runs, what is charged and estimated, and stale choices falling back. */
class PreferredModelResolverTest {

    private static final long USER = 42L;

    private UserAiPreferencesRepository repository;
    private AiModelRegistry registry;
    private PreferredModelResolver resolver;
    private UserAiPreferences stored;

    @BeforeEach
    void setUp() {
        AiModelProperties properties = new AiModelProperties();
        properties.getModels().add(model("default-text", AiCapability.TEXT_TO_TEXT, 1, true));
        properties.getModels().add(model("premium-text", AiCapability.TEXT_TO_TEXT, 4, true));
        properties.getModels().add(model("hidden-text", AiCapability.TEXT_TO_TEXT, 9, false));
        properties.getModels().add(model("default-image", AiCapability.TEXT_TO_IMAGE, 5, true));
        properties.getDefaultModel().put("TEXT_TO_TEXT", "default-text");
        properties.getDefaultModel().put("TEXT_TO_IMAGE", "default-image");
        registry = new AiModelRegistry(properties);

        stored = new UserAiPreferences();
        stored.setUserId(USER);
        repository = mock(UserAiPreferencesRepository.class);
        when(repository.findById(USER)).thenAnswer(invocation -> Optional.of(stored));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        resolver = new PreferredModelResolver(repository, registry);
    }

    @Test
    void withoutAChoiceTheDefaultRuns() {
        assertEquals("default-text", resolver.resolve(USER, AiCapability.TEXT_TO_TEXT).getKey());
    }

    @Test
    void aSavedChoiceRunsAndIsCharged() {
        resolver.save(USER, Map.of(AiCapability.TEXT_TO_TEXT, "premium-text"));
        CreditService credits = mock(CreditService.class);
        when(credits.reserve(anyLong(), anyInt(), any(), anyString())).thenReturn(Optional.of(new CreditReservation("tx-1", 4, AiCapability.TEXT_TO_TEXT, "premium-text")));

        ModelSelectionOutcome outcome = new ModelSelectionService(registry, credits, resolver)
                .select(USER, AiCapability.TEXT_TO_TEXT, null);

        assertEquals("premium-text", outcome.model().getKey());
        verify(credits).reserve(eq(USER), eq(4), eq(AiCapability.TEXT_TO_TEXT), eq("premium-text"));
    }

    @Test
    void theEstimateUsesTheChosenModelsCost() {
        resolver.save(USER, Map.of(AiCapability.TEXT_TO_TEXT, "premium-text"));
        RecipeAiWorkflowEstimator estimator =
                new RecipeAiWorkflowEstimator(registry, new RecipeAiWorkflowProperties(), resolver);

        var estimate = estimator.estimate(USER, List.of(RecipeAiTaskType.PROCESS),
                estimator.assumedStepCounts());

        assertEquals(4, estimate.getMinCredits());
        assertEquals(8, estimate.getMaxCredits());
    }

    @Test
    void aChoiceThatIsNoLongerOfferedFallsBackToTheDefault() {
        resolver.save(USER, Map.of(AiCapability.TEXT_TO_TEXT, "premium-text"));
        registry.apply(Map.of("premium-text", m -> m.setUserSelectable(false)), Map.of());

        assertEquals("default-text", resolver.resolve(USER, AiCapability.TEXT_TO_TEXT).getKey());
    }

    @Test
    void aRunsChoiceBeatsTheSavedOneOnlyWhileItsScopeIsOpen() {
        resolver.save(USER, Map.of(AiCapability.TEXT_TO_TEXT, "default-text"));

        try (AiRunModels.Scope ignored = AiRunModels.open(Map.of("TEXT_TO_TEXT", "premium-text"))) {
            assertEquals("premium-text", resolver.resolve(USER, AiCapability.TEXT_TO_TEXT).getKey());
            assertEquals("default-image", resolver.resolve(USER, AiCapability.TEXT_TO_IMAGE).getKey());
        }
        assertEquals("default-text", resolver.resolve(USER, AiCapability.TEXT_TO_TEXT).getKey());
    }

    @Test
    void aRunsChoiceReachesWorkOnPoolThreads() throws Exception {
        ThreadPoolTaskPool pool = new ThreadPoolTaskPool(1, "run-models-test");
        try (AiRunModels.Scope ignored = AiRunModels.open(Map.of("TEXT_TO_TEXT", "premium-text"))) {
            String key = pool.submit(new NamedTask<>("t", () -> resolver.resolve(USER, AiCapability.TEXT_TO_TEXT).getKey()))
                    .get(5, java.util.concurrent.TimeUnit.SECONDS).value();
            assertEquals("premium-text", key);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void aRunsChoiceThatIsNotOfferedIsRejectedUpFrontAndIgnoredLater() {
        assertThrows(IllegalArgumentException.class, () -> resolver.validateRunChoices(Map.of("TEXT_TO_TEXT", "hidden-text")));
        assertEquals(Map.of(), resolver.validateRunChoices(Map.of("TEXT_TO_TEXT", " ")));

        try (AiRunModels.Scope ignored = AiRunModels.open(Map.of("TEXT_TO_TEXT", "hidden-text"))) {
            assertEquals("default-text", resolver.resolve(USER, AiCapability.TEXT_TO_TEXT).getKey());
        }
    }

    @Test
    void savingAModelUsersCantChooseIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.save(USER, Map.of(AiCapability.TEXT_TO_TEXT, "hidden-text")));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.save(USER, Map.of(AiCapability.TEXT_TO_TEXT, "default-image")));
    }

    @Test
    void aBlankChoiceClearsIt() {
        resolver.save(USER, Map.of(AiCapability.TEXT_TO_TEXT, "premium-text"));
        java.util.Map<AiCapability, String> clear = new java.util.EnumMap<>(AiCapability.class);
        clear.put(AiCapability.TEXT_TO_TEXT, null);
        resolver.save(USER, clear);

        assertEquals("default-text", resolver.resolve(USER, AiCapability.TEXT_TO_TEXT).getKey());
    }

    private static ModelDefinition model(String key, AiCapability capability, int cost, boolean selectable) {
        ModelDefinition model = new ModelDefinition();
        model.setKey(key);
        model.setCapability(capability);
        model.setTier(ModelTier.PAID);
        model.setCreditCost(cost);
        model.setUserSelectable(selectable);
        return model;
    }
}
