package com.processVisualisation.virtualKitchen.recipe.repository;

import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeTimestampCallbacksTest {

    private final RecipeTimestampCallbacks callbacks = new RecipeTimestampCallbacks();

    @Test
    void newRecipe_getsBothDates() {
        RecipeTemplate recipe = callbacks.recipeTimestamps().onBeforeConvert(new RecipeTemplate(), "process_template");

        assertNotNull(recipe.getCreatedAt());
        assertEquals(recipe.getCreatedAt(), recipe.getUpdatedAt());
    }

    @Test
    void savedAgain_keepsCreatedAt_andMovesUpdatedAt() {
        LocalDateTime created = LocalDateTime.of(2026, 1, 1, 10, 0);
        RecipeTemplate recipe = new RecipeTemplate();
        recipe.setCreatedAt(created);
        recipe.setUpdatedAt(created);

        callbacks.recipeTimestamps().onBeforeConvert(recipe, "process_template");

        assertEquals(created, recipe.getCreatedAt());
        assertTrue(recipe.getUpdatedAt().isAfter(created));
    }

    @Test
    void process_getsDatesToo() {
        Process process = callbacks.processTimestamps().onBeforeConvert(new Process(), "process");

        assertNotNull(process.getCreatedAt());
        assertNotNull(process.getUpdatedAt());
    }
}
