package com.processVisualisation.virtualKitchen.repository;

import com.processVisualisation.virtualKitchen.model.StepDefinition;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface StepDefinitionRepository extends MongoRepository<StepDefinition, Long> {
}
