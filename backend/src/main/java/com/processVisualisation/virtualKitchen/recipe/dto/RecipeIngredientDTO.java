package com.processVisualisation.virtualKitchen.recipe.dto;

import jakarta.validation.constraints.Size;
import com.processVisualisation.virtualKitchen.common.web.InputLimits;
import com.processVisualisation.virtualKitchen.store.units.UnitType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request/response payload for one line item of a recipe's ingredient list,
 * referencing a global ingredient catalog entry by id plus this recipe's own
 * quantity/notes/preparation. Not yet wired to a controller; prepared for a
 * future {@code PUT /recipes/{id}/ingredients}-style endpoint.
 */
@Data
public class RecipeIngredientDTO {

    @NotNull
    private Long ingredientId;

    @NotNull
    private Double quantity;

    @NotNull
    private UnitType unit;

    @Size(max = InputLimits.NOTE, message = "Notes can be at most " + InputLimits.NOTE + " characters")
    private String notes;
    @Size(max = InputLimits.NOTE, message = "Preparation can be at most " + InputLimits.NOTE + " characters")
    private String preparation;
}
