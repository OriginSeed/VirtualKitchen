package com.processVisualisation.virtualKitchen.service.impl;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.mapper.KitchenMapper;
import com.processVisualisation.virtualKitchen.model.Kitchen;
import com.processVisualisation.virtualKitchen.repository.KitchenRepository;
import com.processVisualisation.virtualKitchen.service.IKitchenService;
import com.processVisualisation.virtualKitchen.service.SequenceGeneratorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class KitchenServiceImpl implements IKitchenService {

    @Autowired
    private KitchenRepository repo;

    @Autowired
    private KitchenMapper mapper;

    @Autowired
    private SequenceGeneratorService seq;

    @Override
    public KitchenResponseDTO create(KitchenRequestDTO dto){
        Kitchen k = mapper.toEntity(dto);
        k.setId(seq.generateSequence(Kitchen.SEQUENCE_NAME));
        return mapper.toDTO(repo.save(k));
    }

    @Override
    public KitchenResponseDTO get(Long id){
        return mapper.toDTO(repo.findById(id).orElseThrow());
    }

    @Override
    public List<KitchenResponseDTO> getByOwner(Long ownerId){
        return repo.findByOwnerId(ownerId)
                .stream()
                .map(mapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public KitchenResponseDTO update(Long id, KitchenUpdateDTO dto){
        Kitchen k = repo.findById(id).orElseThrow();
        k.setName(dto.getName());
        return mapper.toDTO(repo.save(k));
    }

    @Override
    public void delete(Long id){
        repo.deleteById(id);
    }
}
