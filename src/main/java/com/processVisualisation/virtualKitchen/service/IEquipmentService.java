package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.dto.*;

import java.util.List;

public interface IEquipmentService {
    EquipmentResponseDTO create(EquipmentRequestDTO dto);
    EquipmentResponseDTO get(Long id);
    List<EquipmentResponseDTO> getAll();
    void delete(Long id);
}
