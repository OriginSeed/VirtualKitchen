package com.processVisualisation.virtualKitchen.service.impl;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.mapper.IngredientMapper;
import com.processVisualisation.virtualKitchen.model.Ingredient;
import com.processVisualisation.virtualKitchen.repository.IngredientRepository;
import com.processVisualisation.virtualKitchen.service.IIngredientService;
import com.processVisualisation.virtualKitchen.service.SequenceGeneratorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class IngredientServiceImpl implements IIngredientService {

    @Autowired
    private IngredientRepository ingredientRepository;

    @Autowired
    private IngredientMapper ingredientMapper;

    @Autowired
    private SequenceGeneratorService sequenceGeneratorService;

    @Override
    public IngredientResponseDTO createIngredient(IngredientRequestDTO request) {

        if (ingredientRepository.existsByName(request.getName())) {
            throw new RuntimeException("Ingredient already exists");
        }

        Ingredient ingredient = ingredientMapper.toEntity(request);
        ingredient.setId(sequenceGeneratorService.generateSequence(Ingredient.SEQUENCE_NAME));

        Ingredient saved = ingredientRepository.save(ingredient);
        return ingredientMapper.toDTO(saved);
    }

    @Override
    public IngredientResponseDTO getIngredientById(Long id) {
        Ingredient ingredient = ingredientRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Ingredient not found with id: " + id));

        return ingredientMapper.toDTO(ingredient);
    }

    @Override
    public List<IngredientResponseDTO> getAllIngredients() {
        return ingredientRepository.findAll()
                .stream()
                .map(ingredientMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public IngredientResponseDTO updateIngredient(Long id, IngredientUpdateDTO request) {

        Ingredient ingredient = ingredientRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Ingredient not found with id: " + id));

        ingredient.setName(request.getName());
        ingredient.setDescription(request.getDescription());
        ingredient.setDefaultUnit(request.getDefaultUnit());

        Ingredient updated = ingredientRepository.save(ingredient);
        return ingredientMapper.toDTO(updated);
    }

    @Override
    public void deleteIngredient(Long id) {

        Ingredient ingredient = ingredientRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Ingredient not found with id: " + id));

        ingredientRepository.delete(ingredient);
    }
}