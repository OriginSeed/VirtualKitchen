package com.processVisualisation.virtualKitchen.recipe.mapper;

import com.processVisualisation.virtualKitchen.recipe.dto.NutritionInfoDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeDetailResponseDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeIngredientDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeTemplateRequestDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeTemplateResponseDTO;
import com.processVisualisation.virtualKitchen.recipe.model.NutritionInfo;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeIngredient;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import com.processVisualisation.virtualKitchen.recipe.model.Visibility;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralizes conversion between the {@link RecipeTemplate} entity and its
 * {@link RecipeTemplateRequestDTO}/{@link RecipeTemplateResponseDTO}
 * representations, plus (for the new Recipe Tool) its
 * {@link RecipeDetailResponseDTO} view and its embedded
 * {@link RecipeIngredient}/{@link NutritionInfo} conversions.
 */
@Component
public class RecipeTemplateMapper {

    /**
     * Converts an incoming request DTO into a new {@link RecipeTemplate}
     * entity. Lossy/derived field: {@code visibility} is always hardcoded to
     * {@link Visibility#PRIVATE} regardless of the DTO's contents. The
     * entity's {@code id}, {@code createdAt} and {@code updatedAt} are left
     * unset: the id is assigned by the service, the dates on save by
     * {@code RecipeTimestampCallbacks}.
     *
     * @param dto the request payload describing the recipe template to create
     * @return a new, unpersisted {@link RecipeTemplate} entity populated from {@code dto}
     */
    public RecipeTemplate toEntity(RecipeTemplateRequestDTO dto){
        RecipeTemplate pt = new RecipeTemplate();
        pt.setName(dto.getName());
        pt.setDescription(dto.getDescription());
        pt.setCreatedBy(dto.getCreatedBy());
        pt.setVisibility(Visibility.PRIVATE);
        return pt;
    }

    /**
     * Converts a {@link RecipeTemplate} entity into its response DTO
     * representation for returning to clients.
     *
     * @param pt the entity to convert
     * @return a fully populated {@link RecipeTemplateResponseDTO}
     */
    public RecipeTemplateResponseDTO toDTO(RecipeTemplate pt){
        return RecipeTemplateResponseDTO.builder()
                .id(pt.getId())
                .name(pt.getName())
                .description(pt.getDescription())
                .createdBy(pt.getCreatedBy())
                .visibility(pt.getVisibility())
                .copiedFromRecipeId(pt.getCopiedFromRecipeId())
                .createdAt(pt.getCreatedAt())
                .updatedAt(pt.getUpdatedAt())
                .build();
    }

    /**
     * Converts a {@link RecipeTemplate} entity into the new Recipe Tool's
     * detail view, including its ingredients, nutrition and main process id.
     *
     * @param pt the entity to convert
     * @return a fully populated {@link RecipeDetailResponseDTO}
     */
    public RecipeDetailResponseDTO toDetailDTO(RecipeTemplate pt) {
        return RecipeDetailResponseDTO.builder()
                .id(pt.getId())
                .name(pt.getName())
                .description(pt.getDescription())
                .createdBy(pt.getCreatedBy())
                .visibility(pt.getVisibility())
                .copiedFromRecipeId(pt.getCopiedFromRecipeId())
                .ingredients(toIngredientDTOs(pt.getIngredients()))
                .nutrition(toNutritionDTO(pt.getNutrition()))
                .preparationNotes(pt.getPreparationNotes())
                .mainProcessId(pt.getMainProcessId())
                .processRevision(pt.getProcessRevision() != null ? pt.getProcessRevision() : 0L)
                .createdAt(pt.getCreatedAt())
                .updatedAt(pt.getUpdatedAt())
                .build();
    }

    /**
     * Converts a list of ingredient entities into DTOs.
     *
     * @param ingredients the entities to convert, may be null
     * @return the converted DTOs, empty if ingredients was null
     */
    public List<RecipeIngredientDTO> toIngredientDTOs(List<RecipeIngredient> ingredients) {
        List<RecipeIngredientDTO> result = new ArrayList<>();
        if (ingredients == null) {
            return result;
        }
        for (RecipeIngredient ingredient : ingredients) {
            result.add(toIngredientDTO(ingredient));
        }
        return result;
    }

    /**
     * Converts a single ingredient entity into its DTO.
     *
     * @param ingredient the entity to convert
     * @return the converted {@link RecipeIngredientDTO}
     */
    public RecipeIngredientDTO toIngredientDTO(RecipeIngredient ingredient) {
        RecipeIngredientDTO dto = new RecipeIngredientDTO();
        dto.setIngredientId(ingredient.getIngredientId());
        dto.setQuantity(ingredient.getQuantity());
        dto.setUnit(ingredient.getUnit());
        dto.setNotes(ingredient.getNotes());
        dto.setPreparation(ingredient.getPreparation());
        return dto;
    }

    /**
     * Converts a list of ingredient DTOs into new, unpersisted
     * {@link RecipeIngredient} entities, for replacing a recipe's ingredient
     * list on update.
     *
     * @param dtos the DTOs to convert, may be null
     * @return the converted entities, empty if dtos was null
     */
    public List<RecipeIngredient> toIngredientEntities(List<RecipeIngredientDTO> dtos) {
        List<RecipeIngredient> result = new ArrayList<>();
        if (dtos == null) {
            return result;
        }
        for (RecipeIngredientDTO dto : dtos) {
            RecipeIngredient ingredient = new RecipeIngredient();
            ingredient.setIngredientId(dto.getIngredientId());
            ingredient.setQuantity(dto.getQuantity() != null ? dto.getQuantity() : 0d);
            ingredient.setUnit(dto.getUnit());
            ingredient.setNotes(dto.getNotes());
            ingredient.setPreparation(dto.getPreparation());
            result.add(ingredient);
        }
        return result;
    }

    /**
     * Converts a {@link NutritionInfo} entity into its DTO.
     *
     * @param nutrition the entity to convert, may be null
     * @return the converted DTO, or null if nutrition was null
     */
    public NutritionInfoDTO toNutritionDTO(NutritionInfo nutrition) {
        if (nutrition == null) {
            return null;
        }
        NutritionInfoDTO dto = new NutritionInfoDTO();
        dto.setCalories(nutrition.getCalories());
        dto.setProteinGrams(nutrition.getProteinGrams());
        dto.setCarbohydratesGrams(nutrition.getCarbohydratesGrams());
        dto.setFatGrams(nutrition.getFatGrams());
        dto.setFiberGrams(nutrition.getFiberGrams());
        dto.setSodiumMilligrams(nutrition.getSodiumMilligrams());
        dto.setServings(nutrition.getServings());
        return dto;
    }

    /**
     * Converts a nutrition DTO into a new {@link NutritionInfo} entity.
     *
     * @param dto the DTO to convert, may be null
     * @return the converted entity, or null if dto was null
     */
    public NutritionInfo toNutritionEntity(NutritionInfoDTO dto) {
        if (dto == null) {
            return null;
        }
        NutritionInfo nutrition = new NutritionInfo();
        nutrition.setCalories(dto.getCalories());
        nutrition.setProteinGrams(dto.getProteinGrams());
        nutrition.setCarbohydratesGrams(dto.getCarbohydratesGrams());
        nutrition.setFatGrams(dto.getFatGrams());
        nutrition.setFiberGrams(dto.getFiberGrams());
        nutrition.setSodiumMilligrams(dto.getSodiumMilligrams());
        nutrition.setServings(dto.getServings());
        return nutrition;
    }
}
