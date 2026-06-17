package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.dto.IngredientRequestDTO;
import com.processVisualisation.virtualKitchen.dto.IngredientResponseDTO;
import com.processVisualisation.virtualKitchen.dto.IngredientUpdateDTO;

import java.util.List;

public interface IIngredientService {

    IngredientResponseDTO createIngredient(IngredientRequestDTO request);

    IngredientResponseDTO getIngredientById(Long id);

    List<IngredientResponseDTO> getAllIngredients();

    IngredientResponseDTO updateIngredient(Long id, IngredientUpdateDTO request);

    void deleteIngredient(Long id);
}