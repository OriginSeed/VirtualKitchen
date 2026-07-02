package com.processVisualisation.virtualKitchen.repository;

import com.processVisualisation.virtualKitchen.model.VisualizationClip;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface VisualizationClipRepository extends MongoRepository<VisualizationClip, Long> {
    List<VisualizationClip> findByProcessTemplateIdOrderByStepOrderAsc(Long processTemplateId);
    Optional<VisualizationClip> findByProcessTemplateIdAndStepOrder(Long processTemplateId, int stepOrder);
}
