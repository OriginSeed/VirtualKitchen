package com.processVisualisation.virtualKitchen.repository;

import com.processVisualisation.virtualKitchen.model.ProcessExecution;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ProcessExecutionRepository extends MongoRepository<ProcessExecution, Long> {

    List<ProcessExecution> findByUserId(Long userId);

    List<ProcessExecution> findByProcessTemplateId(Long processTemplateId);
}
