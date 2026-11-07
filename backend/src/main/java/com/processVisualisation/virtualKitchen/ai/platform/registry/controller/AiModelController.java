package com.processVisualisation.virtualKitchen.ai.platform.registry.controller;

import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiModelRegistry;
import com.processVisualisation.virtualKitchen.ai.platform.registry.ModelDefinition;
import com.processVisualisation.virtualKitchen.ai.platform.registry.dto.ModelDefinitionResponseDTO;
import com.processVisualisation.virtualKitchen.common.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Read-only view of the configured AI model registry — which models exist,
 * their {@code PAID}/{@code OPEN_SOURCE} tier, and whether they're currently
 * enabled. Never used by recipe/visualization business logic itself (that
 * goes through {@code ai.routing.ModelSelectionService}); this exists for a
 * future frontend model picker and for support/debugging.
 */
@RestController
@RequestMapping("/api/v1/ai/models")
public class AiModelController {

    private final AiModelRegistry registry;

    public AiModelController(AiModelRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public ApiResponse<List<ModelDefinitionResponseDTO>> list(
            @RequestParam(required = false) AiCapability capability
    ) {
        List<ModelDefinition> models = capability != null
                ? registry.byCapability(capability)
                : Arrays.stream(AiCapability.values())
                        .flatMap(c -> registry.byCapability(c).stream())
                        .toList();

        List<ModelDefinitionResponseDTO> response = models.stream().map(this::toDto).collect(Collectors.toList());

        return ApiResponse.<List<ModelDefinitionResponseDTO>>builder()
                .success(true)
                .message("AI model registry")
                .data(response)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /** The models users may choose for {@code capability} (enabled and user-selectable), for the model picker. */
    @GetMapping("/selectable")
    public ApiResponse<List<ModelDefinitionResponseDTO>> selectable(@RequestParam AiCapability capability) {
        List<ModelDefinitionResponseDTO> response = registry.userSelectable(capability).stream()
                .map(this::toDto)
                .toList();
        return ApiResponse.<List<ModelDefinitionResponseDTO>>builder()
                .success(true)
                .message("Selectable AI models")
                .data(response)
                .timestamp(LocalDateTime.now())
                .build();
    }

    private ModelDefinitionResponseDTO toDto(ModelDefinition model) {
        return ModelDefinitionResponseDTO.builder()
                .key(model.getKey())
                .capability(model.getCapability())
                .tier(model.getTier())
                .providerModelId(model.getProviderModelId())
                .creditCost(model.getCreditCost())
                .enabled(model.isEnabled())
                .displayName(model.displayNameOrKey())
                .userSelectable(model.isUserSelectable())
                .defaultModel(model.getKey().equals(registry.defaultKeyFor(model.getCapability())))
                .build();
    }
}
