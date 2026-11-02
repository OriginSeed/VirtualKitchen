package com.processVisualisation.virtualKitchen.ai.platform.preference;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserAiPreferencesRepository extends MongoRepository<UserAiPreferences, Long> {
}
