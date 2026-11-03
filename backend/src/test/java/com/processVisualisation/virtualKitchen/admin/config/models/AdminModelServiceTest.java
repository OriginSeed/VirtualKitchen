package com.processVisualisation.virtualKitchen.admin.config.models;

import com.processVisualisation.virtualKitchen.admin.config.RuntimeConfigService;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.pricing.PricingRegistry;
import com.processVisualisation.virtualKitchen.ai.platform.credit.CreditReservation;
import com.processVisualisation.virtualKitchen.ai.platform.credit.CreditService;
import com.processVisualisation.virtualKitchen.ai.platform.dispatch.AiClientResolver;
import com.processVisualisation.virtualKitchen.ai.platform.preference.UserAiPreferences;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelProperties;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelRegistry;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelTier;
import com.processVisualisation.virtualKitchen.ai.platform.routing.ModelSelectionService;
import com.processVisualisation.virtualKitchen.ai.provider.AiClient;
import com.processVisualisation.virtualKitchen.ai.provider.ImageGenerationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Models added from the admin panel: usable at once, validated, rolled back on rejection, deleted only when safe. */
class AdminModelServiceTest {

    private static final long ADMIN = 1L;

    private final Map<String, AdminModel> stored = new LinkedHashMap<>();
    private AiModelRegistry registry;
    private RuntimeConfigService configService;
    private MongoTemplate mongoTemplate;
    private PricingRegistry pricing;
    private AdminModelService service;

    @BeforeEach
    void setUp() {
        AiModelProperties properties = new AiModelProperties();
        properties.getModels().add(model("deepseek", AiCapability.TEXT_TO_TEXT, "deepInfraAiClient"));
        properties.getModels().add(model("flux", AiCapability.TEXT_TO_IMAGE, "deepInfraImageClient"));
        properties.getDefaultModel().put("TEXT_TO_TEXT", "deepseek");
        properties.getDefaultModel().put("TEXT_TO_IMAGE", "flux");
        registry = new AiModelRegistry(properties);

        AdminModelRepository repository = mock(AdminModelRepository.class);
        when(repository.findAll()).thenAnswer(i -> List.copyOf(stored.values()));
        when(repository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(stored.get(i.<String>getArgument(0))));
        when(repository.existsById(anyString())).thenAnswer(i -> stored.containsKey(i.<String>getArgument(0)));
        when(repository.save(any())).thenAnswer(i -> {
            AdminModel model = i.getArgument(0);
            stored.put(model.getKey(), model);
            return model;
        });
        doAnswer(i -> stored.remove(i.<String>getArgument(0))).when(repository).deleteById(anyString());

        // Stands in for the Models & Credits section: re-applies the stored added models to the registry.
        configService = mock(RuntimeConfigService.class);
        doAnswer(i -> {
            registry.apply(stored.values().stream().map(AdminModel::toDefinition).toList(), Map.of(), Map.of());
            return null;
        }).when(configService).reapply("models");

        AiClientResolver clients = new AiClientResolver(
                Map.of("deepInfraAiClient", mock(AiClient.class), "ollamaAiClient", mock(AiClient.class)),
                Map.of("deepInfraImageClient", mock(ImageGenerationClient.class),
                        "drawThingsImageClient", mock(ImageGenerationClient.class)));
        mongoTemplate = mock(MongoTemplate.class);
        pricing = mock(PricingRegistry.class);
        service = new AdminModelService(repository, registry, clients, configService, mongoTemplate, pricing);
    }

    @Test
    void anAddedModelIsSelectableAtOnceAndChargedItsCost() {
        service.add(request("qwen3", AiCapability.TEXT_TO_TEXT, "deepInfraAiClient", 3, true), ADMIN);

        ModelDefinition added = registry.find("qwen3").orElseThrow();
        assertTrue(added.isAdminAdded());
        assertEquals("Qwen/Qwen3-235B", added.getProviderModelId());
        assertEquals(List.of("qwen3"), registry.userSelectable(AiCapability.TEXT_TO_TEXT).stream().map(ModelDefinition::getKey).toList());

        CreditService credits = mock(CreditService.class);
        when(credits.reserve(anyLong(), anyInt(), any(), anyString()))
                .thenReturn(Optional.of(new CreditReservation("tx", 3, AiCapability.TEXT_TO_TEXT, "qwen3")));
        assertEquals("qwen3", new ModelSelectionService(registry, credits).select(7L, AiCapability.TEXT_TO_TEXT, "qwen3").model().getKey());
        verify(credits).reserve(eq(7L), eq(3), eq(AiCapability.TEXT_TO_TEXT), eq("qwen3"));
        verify(configService).audit(eq("models"), eq("MODEL_ADD"), eq(ADMIN), any(), any());
        verify(pricing).publishAdminCard(any(ModelDefinition.class), any());
    }

    @Test
    void aClashingKeyIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.add(request("deepseek", AiCapability.TEXT_TO_TEXT, "deepInfraAiClient", 1, false), ADMIN));
        assertThrows(IllegalArgumentException.class,
                () -> service.add(request("Bad Key", AiCapability.TEXT_TO_TEXT, "deepInfraAiClient", 1, false), ADMIN));
        assertTrue(stored.isEmpty());
    }

    @Test
    void aProviderOfAnotherCapabilityOrWithAFixedModelIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.add(request("x", AiCapability.TEXT_TO_TEXT, "deepInfraImageClient", 1, false), ADMIN));
        assertThrows(IllegalArgumentException.class,
                () -> service.add(request("y", AiCapability.TEXT_TO_IMAGE, "drawThingsImageClient", 1, false), ADMIN));
        assertFalse(service.options().providers().get(AiCapability.TEXT_TO_IMAGE).stream()
                .anyMatch(choice -> choice.value().equals("drawThingsImageClient")));
    }

    @Test
    void aModelTheRegistryRejectsIsRolledBack() {
        AdminModelService.ModelRequest crossFallback = new AdminModelService.ModelRequest("bad", AiCapability.TEXT_TO_TEXT,
                "deepInfraAiClient", "some/model", "Bad", ModelTier.PAID, 1, "flux", true, false, null, null, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.add(crossFallback, ADMIN));
        assertTrue(stored.isEmpty());
        assertTrue(registry.find("bad").isEmpty());
        verify(configService, never()).audit(eq("models"), eq("MODEL_ADD"), anyLong(), any(), any());
    }

    @Test
    void editingChangesTheProviderModelId() {
        service.add(request("qwen3", AiCapability.TEXT_TO_TEXT, "deepInfraAiClient", 1, false), ADMIN);

        service.update("qwen3", new AdminModelService.ModelRequest(null, null, null, "Qwen/Qwen3-32B", "Qwen3 32B",
                null, null, null, null, null, 0.1, 0.3, null, null), ADMIN);

        assertEquals("Qwen/Qwen3-32B", registry.find("qwen3").orElseThrow().getProviderModelId());
        verify(configService).audit(eq("models"), eq("MODEL_EDIT"), eq(ADMIN), any(), any());
    }

    @Test
    void deletingIsRefusedWhileAnotherModelFallsBackToIt() {
        service.add(request("qwen3", AiCapability.TEXT_TO_TEXT, "deepInfraAiClient", 1, false), ADMIN);
        service.add(new AdminModelService.ModelRequest("qwen-big", AiCapability.TEXT_TO_TEXT, "deepInfraAiClient",
                "Qwen/Big", "Big", ModelTier.PAID, 2, "qwen3", true, false, null, null, null, null), ADMIN);

        assertThrows(IllegalArgumentException.class, () -> service.delete("qwen3", ADMIN));
        assertTrue(stored.containsKey("qwen3"));
    }

    @Test
    void deletingIsRefusedWhileUsersChoseIt() {
        service.add(request("qwen3", AiCapability.TEXT_TO_TEXT, "deepInfraAiClient", 1, true), ADMIN);
        when(mongoTemplate.count(any(Query.class), eq(UserAiPreferences.class))).thenReturn(2L);

        assertThrows(IllegalArgumentException.class, () -> service.delete("qwen3", ADMIN));
    }

    @Test
    void deletingAnUnusedModelRemovesItAndItsOverrides() {
        service.add(request("qwen3", AiCapability.TEXT_TO_TEXT, "deepInfraAiClient", 1, false), ADMIN);

        service.delete("qwen3", ADMIN);

        assertTrue(registry.find("qwen3").isEmpty());
        verify(configService).dropOverrides(eq("models"), any(), eq(ADMIN));
        verify(configService).audit(eq("models"), eq("MODEL_DELETE"), eq(ADMIN), any(), any());
    }

    @Test
    void configuredModelsCantBeDeleted() {
        assertThrows(IllegalArgumentException.class, () -> service.delete("deepseek", ADMIN));
    }

    private static AdminModelService.ModelRequest request(String key, AiCapability capability, String provider,
                                                          int cost, boolean selectable) {
        return new AdminModelService.ModelRequest(key, capability, provider, "Qwen/Qwen3-235B", "Qwen3",
                ModelTier.PAID, cost, null, true, selectable, null, null, null, null);
    }

    private static ModelDefinition model(String key, AiCapability capability, String bean) {
        ModelDefinition model = new ModelDefinition();
        model.setKey(key);
        model.setCapability(capability);
        model.setTier(ModelTier.PAID);
        model.setProviderBean(bean);
        model.setCreditCost(1);
        return model;
    }
}
