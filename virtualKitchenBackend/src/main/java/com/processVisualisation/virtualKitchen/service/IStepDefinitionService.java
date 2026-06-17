package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.dto.*;

import java.util.List;

public interface IStepDefinitionService {

    StepDefinitionResponseDTO create(StepDefinitionRequestDTO dto);

    List<StepDefinitionResponseDTO> getAll();
}
