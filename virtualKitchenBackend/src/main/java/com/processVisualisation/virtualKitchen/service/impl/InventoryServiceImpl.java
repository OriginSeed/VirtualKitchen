package com.processVisualisation.virtualKitchen.service.impl;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.mapper.InventoryMapper;
import com.processVisualisation.virtualKitchen.model.Inventory;
import com.processVisualisation.virtualKitchen.repository.InventoryRepository;
import com.processVisualisation.virtualKitchen.service.IInventoryService;
import com.processVisualisation.virtualKitchen.service.SequenceGeneratorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class InventoryServiceImpl implements IInventoryService {

    @Autowired
    private InventoryRepository repo;

    @Autowired
    private InventoryMapper mapper;

    @Autowired
    private SequenceGeneratorService seq;

    @Override
    public InventoryResponseDTO addOrUpdate(InventoryRequestDTO dto){

        Inventory inv = repo.findByUserIdAndItemTypeAndItemId(
                dto.getUserId(), dto.getItemType(), dto.getItemId()
        ).orElse(null);

        if(inv == null){
            inv = mapper.toEntity(dto);
            inv.setId(seq.generateSequence(Inventory.SEQUENCE_NAME));
        } else {
            inv.setQuantity(inv.getQuantity() + dto.getQuantity());
        }

        inv.setLastUpdated(LocalDateTime.now());

        return mapper.toDTO(repo.save(inv));
    }

    @Override
    public List<InventoryResponseDTO> getByUser(Long userId){
        return repo.findByUserId(userId)
                .stream()
                .map(mapper::toDTO)
                .collect(Collectors.toList());
    }
}
