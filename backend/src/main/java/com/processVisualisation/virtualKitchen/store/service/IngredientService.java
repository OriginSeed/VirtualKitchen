package com.processVisualisation.virtualKitchen.store.service;

import com.processVisualisation.virtualKitchen.common.persistence.SequenceGeneratorService;
import com.processVisualisation.virtualKitchen.store.catalog.IngredientCatalogChangedEvent;
import com.processVisualisation.virtualKitchen.store.dto.IngredientRequestDTO;
import com.processVisualisation.virtualKitchen.store.dto.IngredientResponseDTO;
import com.processVisualisation.virtualKitchen.store.dto.IngredientUpdateDTO;
import com.processVisualisation.virtualKitchen.store.mapper.IngredientMapper;
import com.processVisualisation.virtualKitchen.store.model.Ingredient;
import com.processVisualisation.virtualKitchen.store.repository.IngredientRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Ingredient catalog CRUD, backed by MongoDB via
 * {@link IngredientRepository}. Assigns identifiers using
 * {@link SequenceGeneratorService} and converts between entities and DTOs
 * using {@link IngredientMapper}.
 */
@Service
public class IngredientService {

    private static final Logger log = LoggerFactory.getLogger(IngredientService.class);

    @Autowired
    private IngredientRepository ingredientRepository;

    @Autowired
    private IngredientMapper ingredientMapper;

    @Autowired
    private SequenceGeneratorService sequenceGeneratorService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * Creates a new ingredient catalog entry after checking that the name
     * is not already in use, assigning it a generated sequence id.
     *
     * @param request the ingredient name, description and default unit
     * @return the created ingredient
     * @throws RuntimeException if an ingredient with the same name already exists
     */
    public IngredientResponseDTO createIngredient(IngredientRequestDTO request) {

        if (ingredientRepository.existsByName(request.getName())) {
            log.warn("event=ingredient_create_rejected reason=duplicate_name");
            throw new RuntimeException("Ingredient already exists");
        }

        Ingredient ingredient = ingredientMapper.toEntity(request);
        ingredient.setId(sequenceGeneratorService.generateSequence(Ingredient.SEQUENCE_NAME));

        Ingredient saved = ingredientRepository.save(ingredient);
        eventPublisher.publishEvent(new IngredientCatalogChangedEvent("create"));
        log.info("event=ingredient_created ingredientId={} defaultUnit={}", ingredient.getId(), ingredient.getDefaultUnit());
        return ingredientMapper.toDTO(saved);
    }

    /**
     * Fetches a single ingredient by its identifier.
     *
     * @param id the ingredient identifier
     * @return the matching ingredient
     * @throws RuntimeException if no ingredient exists with the given id
     */
    public IngredientResponseDTO getIngredientById(Long id) {
        Ingredient ingredient = ingredientRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Ingredient not found with id: " + id));

        return ingredientMapper.toDTO(ingredient);
    }

    /**
     * Fetches every ingredient in the catalog.
     *
     * @return the list of all ingredients
     */
    public List<IngredientResponseDTO> getAllIngredients() {
        return ingredientRepository.findAll()
                .stream()
                .map(ingredientMapper::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Updates an existing ingredient's name, description and default unit.
     *
     * @param id the identifier of the ingredient to update
     * @param request the replacement values
     * @return the updated ingredient
     * @throws RuntimeException if no ingredient exists with the given id
     */
    public IngredientResponseDTO updateIngredient(Long id, IngredientUpdateDTO request) {

        Ingredient ingredient = ingredientRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Ingredient not found with id: " + id));

        ingredient.setName(request.getName());
        ingredient.setDescription(request.getDescription());
        ingredient.setDefaultUnit(request.getDefaultUnit());
        ingredient.setImageUrl(request.getImageUrl());

        Ingredient updated = ingredientRepository.save(ingredient);
        eventPublisher.publishEvent(new IngredientCatalogChangedEvent("update"));
        log.info("event=ingredient_updated ingredientId={} defaultUnit={}", id, ingredient.getDefaultUnit());
        return ingredientMapper.toDTO(updated);
    }

    /**
     * Deletes an ingredient from the catalog.
     *
     * @param id the identifier of the ingredient to delete
     * @throws RuntimeException if no ingredient exists with the given id
     */
    public void deleteIngredient(Long id) {

        Ingredient ingredient = ingredientRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Ingredient not found with id: " + id));

        ingredientRepository.delete(ingredient);
        eventPublisher.publishEvent(new IngredientCatalogChangedEvent("delete"));
        log.info("event=ingredient_deleted ingredientId={}", id);
    }
}