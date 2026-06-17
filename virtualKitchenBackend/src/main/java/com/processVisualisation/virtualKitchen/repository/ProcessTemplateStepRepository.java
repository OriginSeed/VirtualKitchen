package com.processVisualisation.virtualKitchen.repository;

import com.processVisualisation.virtualKitchen.model.ProcessTemplateStep;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ProcessTemplateStepRepository extends MongoRepository<ProcessTemplateStep, Long> {

    List<ProcessTemplateStep> findByProcessTemplateIdOrderByStepOrderAsc(Long processTemplateId);
}
