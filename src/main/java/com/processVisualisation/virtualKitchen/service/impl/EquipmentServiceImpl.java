package com.processVisualisation.virtualKitchen.service.impl;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.mapper.EquipmentMapper;
import com.processVisualisation.virtualKitchen.model.Equipment;
import com.processVisualisation.virtualKitchen.repository.EquipmentRepository;
import com.processVisualisation.virtualKitchen.service.IEquipmentService;
import com.processVisualisation.virtualKitchen.service.SequenceGeneratorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class EquipmentServiceImpl implements IEquipmentService {

    @Autowired
    private EquipmentRepository repo;

    @Autowired
    private EquipmentMapper mapper;

    @Autowired
    private SequenceGeneratorService seq;

    @Override
    public EquipmentResponseDTO create(EquipmentRequestDTO dto){
        if(repo.existsByName(dto.getName())){
            throw new RuntimeException("Already exists");
        }
        Equipment e = mapper.toEntity(dto);
        e.setId(seq.generateSequence(Equipment.SEQUENCE_NAME));
        return mapper.toDTO(repo.save(e));
    }

    @Override
    public EquipmentResponseDTO get(Long id){
        return mapper.toDTO(repo.findById(id).orElseThrow());
    }

    @Override
    public List<EquipmentResponseDTO> getAll(){
        return repo.findAll().stream().map(mapper::toDTO).collect(Collectors.toList());
    }

    @Override
    public void delete(Long id){
        repo.deleteById(id);
    }
}
