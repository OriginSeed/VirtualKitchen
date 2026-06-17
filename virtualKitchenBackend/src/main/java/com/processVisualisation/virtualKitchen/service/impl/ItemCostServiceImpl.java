package com.processVisualisation.virtualKitchen.service.impl;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.mapper.ItemCostMapper;
import com.processVisualisation.virtualKitchen.model.ItemCost;
import com.processVisualisation.virtualKitchen.repository.ItemCostRepository;
import com.processVisualisation.virtualKitchen.service.IItemCostService;
import com.processVisualisation.virtualKitchen.service.SequenceGeneratorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ItemCostServiceImpl implements IItemCostService {

    @Autowired
    private ItemCostRepository repo;

    @Autowired
    private ItemCostMapper mapper;

    @Autowired
    private SequenceGeneratorService seq;

    @Override
    public ItemCostResponseDTO create(ItemCostRequestDTO dto){
        ItemCost cost = mapper.toEntity(dto);
        cost.setId(seq.generateSequence(ItemCost.SEQUENCE_NAME));
        return mapper.toDTO(repo.save(cost));
    }
}
