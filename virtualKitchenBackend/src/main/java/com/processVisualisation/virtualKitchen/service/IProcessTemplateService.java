package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.dto.*;

import java.util.List;

public interface IProcessTemplateService {

    ProcessTemplateResponseDTO create(ProcessTemplateRequestDTO dto);

    ProcessTemplateResponseDTO get(Long id);

    List<ProcessTemplateResponseDTO> getByUser(Long userId);

    ProcessTemplateResponseDTO update(Long id, ProcessTemplateUpdateDTO dto);

    void delete(Long id);
}
