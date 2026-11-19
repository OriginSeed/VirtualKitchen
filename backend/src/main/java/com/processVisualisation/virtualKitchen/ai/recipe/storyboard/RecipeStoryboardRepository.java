package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.RecipeStoryboard;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.StoryboardStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/** Storyboard generations ({@code recipe_storyboard}). */
public interface RecipeStoryboardRepository extends MongoRepository<RecipeStoryboard, String> {

    /** The generation currently running for a recipe, if any (see {@link RecipeStoryboard#getActiveKey()}). */
    Optional<RecipeStoryboard> findByActiveKey(String activeKey);

    /** The recipe's newest generation in this status (COMPLETED: the recipe's storyboard). */
    Optional<RecipeStoryboard> findFirstByRecipeIdAndStatusOrderByCreatedAtDesc(Long recipeId, StoryboardStatus status);

    /** The recipe's newest generation, whatever its status. */
    Optional<RecipeStoryboard> findFirstByRecipeIdOrderByCreatedAtDesc(Long recipeId);
}
