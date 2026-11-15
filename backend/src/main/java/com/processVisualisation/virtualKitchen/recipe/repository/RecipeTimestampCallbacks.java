package com.processVisualisation.virtualKitchen.recipe.repository;

import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;

import java.time.LocalDateTime;

/**
 * Stamps {@code createdAt}/{@code updatedAt} on every whole-document save of a recipe or process.
 * <p>
 * Their {@code @CreatedDate}/{@code @LastModifiedDate} annotations do nothing: Mongo auditing is not enabled, and
 * even if it were, ids are assigned from a sequence before the first save, so Spring Data would treat every save as
 * an update and never fill {@code @CreatedDate}. Without these dates the recipe list could not be sorted by date.
 * <p>
 * Atomic {@code @Update} queries bypass this; {@code RecipeTemplateRepository.touchUpdatedAt} covers the recipe's
 * own timestamp when one of its processes changes.
 */
@Configuration
public class RecipeTimestampCallbacks {

    @Bean
    BeforeConvertCallback<RecipeTemplate> recipeTimestamps() {
        return (recipe, collection) -> {
            LocalDateTime now = LocalDateTime.now();
            if (recipe.getCreatedAt() == null) {
                recipe.setCreatedAt(now);
            }
            recipe.setUpdatedAt(now);
            return recipe;
        };
    }

    @Bean
    BeforeConvertCallback<Process> processTimestamps() {
        return (process, collection) -> {
            LocalDateTime now = LocalDateTime.now();
            if (process.getCreatedAt() == null) {
                process.setCreatedAt(now);
            }
            process.setUpdatedAt(now);
            return process;
        };
    }
}
