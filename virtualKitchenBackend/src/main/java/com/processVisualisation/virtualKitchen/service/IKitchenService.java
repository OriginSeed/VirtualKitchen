package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.dto.*;

import java.util.List;

public interface IKitchenService {

    KitchenResponseDTO create(KitchenRequestDTO dto);

    KitchenResponseDTO get(Long id);

    List<KitchenResponseDTO> getByOwner(Long ownerId);

    KitchenResponseDTO update(Long id, KitchenUpdateDTO dto);

    void delete(Long id);
}
