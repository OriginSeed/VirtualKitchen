package com.processVisualisation.virtualKitchen.service.impl;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.mapper.ProcessEquipmentUsageMapper;
import com.processVisualisation.virtualKitchen.model.ProcessEquipmentUsage;
import com.processVisualisation.virtualKitchen.repository.ProcessEquipmentUsageRepository;
import com.processVisualisation.virtualKitchen.service.IProcessEquipmentUsageService;
import com.processVisualisation.virtualKitchen.service.SequenceGeneratorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProcessEquipmentUsageServiceImpl implements IProcessEquipmentUsageService {

    @Autowired
    private ProcessEquipmentUsageRepository repo;

    @Autowired
    private ProcessEquipmentUsageMapper mapper;

    @Autowired
    private SequenceGeneratorService seq;

    @Override
    public ProcessEquipmentUsageResponseDTO create(ProcessEquipmentUsageRequestDTO dto){
        ProcessEquipmentUsage p = mapper.toEntity(dto);
        p.setId(seq.generateSequence(ProcessEquipmentUsage.SEQUENCE_NAME));
        return mapper.toDTO(repo.save(p));
    }

    @Override
    public List<ProcessEquipmentUsageResponseDTO> getByProcess(Long processExecutionId){
        return repo.findByProcessExecutionId(processExecutionId)
                .stream()
                .map(mapper::toDTO)
                .collect(Collectors.toList());
    }
}
