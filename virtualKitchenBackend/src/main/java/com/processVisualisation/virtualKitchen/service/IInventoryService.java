package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.dto.*;

import java.util.List;

public interface IInventoryService {

    InventoryResponseDTO addOrUpdate(InventoryRequestDTO dto);

    List<InventoryResponseDTO> getByUser(Long userId);
}
