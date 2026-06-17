package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.dto.*;

import java.util.List;

public interface IProcessExecutionService {

    ProcessExecutionResponseDTO start(ProcessExecutionRequestDTO dto);

    ProcessExecutionResponseDTO updateStatus(Long id, String status);

    List<ProcessExecutionResponseDTO> getByUser(Long userId);
}
