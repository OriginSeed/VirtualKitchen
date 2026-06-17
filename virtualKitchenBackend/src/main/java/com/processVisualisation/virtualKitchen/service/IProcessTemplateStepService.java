package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.dto.*;

import java.util.List;

public interface IProcessTemplateStepService {

    ProcessTemplateStepResponseDTO create(ProcessTemplateStepRequestDTO dto);

    List<ProcessTemplateStepResponseDTO> getSteps(Long processTemplateId);
}
