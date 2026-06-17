package com.processVisualisation.virtualKitchen.service.impl;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.mapper.KitchenInventoryMapper;
import com.processVisualisation.virtualKitchen.model.KitchenInventory;
import com.processVisualisation.virtualKitchen.repository.KitchenInventoryRepository;
import com.processVisualisation.virtualKitchen.service.IKitchenInventoryService;
import com.processVisualisation.virtualKitchen.service.SequenceGeneratorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class KitchenInventoryServiceImpl implements IKitchenInventoryService {

    @Autowired
    private KitchenInventoryRepository repo;

    @Autowired
    private KitchenInventoryMapper mapper;

    @Autowired
    private SequenceGeneratorService seq;

    @Override
    public KitchenInventoryResponseDTO create(KitchenInventoryRequestDTO dto){
        KitchenInventory ki = mapper.toEntity(dto);
        ki.setId(seq.generateSequence(KitchenInventory.SEQUENCE_NAME));
        return mapper.toDTO(repo.save(ki));
    }

    @Override
    public List<KitchenInventoryResponseDTO> getByKitchen(Long kitchenId){
        return repo.findByKitchenId(kitchenId)
                .stream()
                .map(mapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public void delete(Long id){
        repo.deleteById(id);
    }
}
