package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.dto.*;

import java.util.List;

public interface IProcessIngredientUsageService {

    ProcessIngredientUsageResponseDTO create(ProcessIngredientUsageRequestDTO dto);

    List<ProcessIngredientUsageResponseDTO> getByProcess(Long processExecutionId);
}
