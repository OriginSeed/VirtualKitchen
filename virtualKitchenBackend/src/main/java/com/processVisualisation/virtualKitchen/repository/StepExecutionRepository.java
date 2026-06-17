package com.processVisualisation.virtualKitchen.repository;

import com.processVisualisation.virtualKitchen.model.StepExecution;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface StepExecutionRepository extends MongoRepository<StepExecution, Long> {

    List<StepExecution> findByProcessExecutionIdOrderByIdAsc(Long processExecutionId);
}
