package com.processVisualisation.virtualKitchen.repository;

import com.processVisualisation.virtualKitchen.model.ProcessTemplate;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ProcessTemplateRepository extends MongoRepository<ProcessTemplate, Long> {

    List<ProcessTemplate> findByCreatedBy(Long createdBy);
}
