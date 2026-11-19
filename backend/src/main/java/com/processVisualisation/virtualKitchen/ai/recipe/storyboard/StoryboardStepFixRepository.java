package com.processVisualisation.virtualKitchen.ai.recipe.storyboard;

import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.StoryboardStepFix;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface StoryboardStepFixRepository extends MongoRepository<StoryboardStepFix, String> {

    Optional<StoryboardStepFix> findByActiveKey(String activeKey);
}
