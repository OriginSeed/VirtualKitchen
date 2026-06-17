package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.dto.*;

import java.util.List;

public interface IKitchenInventoryService {

    KitchenInventoryResponseDTO create(KitchenInventoryRequestDTO dto);

    List<KitchenInventoryResponseDTO> getByKitchen(Long kitchenId);

    void delete(Long id);
}
