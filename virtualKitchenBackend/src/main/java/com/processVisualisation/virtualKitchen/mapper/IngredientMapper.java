package com.processVisualisation.virtualKitchen.mapper;

import com.processVisualisation.virtualKitchen.dto.IngredientRequestDTO;
import com.processVisualisation.virtualKitchen.dto.IngredientResponseDTO;
import com.processVisualisation.virtualKitchen.model.Ingredient;
import org.springframework.stereotype.Component;

@Component
public class IngredientMapper {

    public Ingredient toEntity(IngredientRequestDTO dto) {
        Ingredient ingredient = new Ingredient();
        ingredient.setName(dto.getName());
        ingredient.setDescription(dto.getDescription());
        ingredient.setDefaultUnit(dto.getDefaultUnit());
        return ingredient;
    }

    public IngredientResponseDTO toDTO(Ingredient ingredient) {
        return IngredientResponseDTO.builder()
                .id(ingredient.getId())
                .name(ingredient.getName())
                .description(ingredient.getDescription())
                .defaultUnit(ingredient.getDefaultUnit())
                .createdAt(ingredient.getCreatedAt())
                .updatedAt(ingredient.getUpdatedAt())
                .build();
    }
}