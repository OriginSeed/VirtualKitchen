package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.dto.*;

import java.util.List;

public interface IProcessEquipmentUsageService {

    ProcessEquipmentUsageResponseDTO create(ProcessEquipmentUsageRequestDTO dto);

    List<ProcessEquipmentUsageResponseDTO> getByProcess(Long processExecutionId);
}
