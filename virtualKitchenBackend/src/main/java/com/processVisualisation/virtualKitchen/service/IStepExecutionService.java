package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.dto.*;

import java.util.List;

public interface IStepExecutionService {

    StepExecutionResponseDTO create(StepExecutionRequestDTO dto);

    StepExecutionResponseDTO update(Long id, StepExecutionUpdateDTO dto);

    List<StepExecutionResponseDTO> getByProcess(Long processExecutionId);
}
