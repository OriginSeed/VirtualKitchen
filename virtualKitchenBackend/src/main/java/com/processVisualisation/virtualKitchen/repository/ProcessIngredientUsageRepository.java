package com.processVisualisation.virtualKitchen.repository;

import com.processVisualisation.virtualKitchen.model.ProcessIngredientUsage;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ProcessIngredientUsageRepository extends MongoRepository<ProcessIngredientUsage, Long> {

    List<ProcessIngredientUsage> findByProcessExecutionId(Long processExecutionId);
}
