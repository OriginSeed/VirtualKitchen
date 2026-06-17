package com.processVisualisation.virtualKitchen.service.impl;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.mapper.ProcessIngredientUsageMapper;
import com.processVisualisation.virtualKitchen.model.ProcessIngredientUsage;
import com.processVisualisation.virtualKitchen.repository.ProcessIngredientUsageRepository;
import com.processVisualisation.virtualKitchen.service.IProcessIngredientUsageService;
import com.processVisualisation.virtualKitchen.service.SequenceGeneratorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProcessIngredientUsageServiceImpl implements IProcessIngredientUsageService {

    @Autowired
    private ProcessIngredientUsageRepository repo;

    @Autowired
    private ProcessIngredientUsageMapper mapper;

    @Autowired
    private SequenceGeneratorService seq;

    @Override
    public ProcessIngredientUsageResponseDTO create(ProcessIngredientUsageRequestDTO dto){
        ProcessIngredientUsage p = mapper.toEntity(dto);
        p.setId(seq.generateSequence(ProcessIngredientUsage.SEQUENCE_NAME));
        return mapper.toDTO(repo.save(p));
    }

    @Override
    public List<ProcessIngredientUsageResponseDTO> getByProcess(Long processExecutionId){
        return repo.findByProcessExecutionId(processExecutionId)
                .stream()
                .map(mapper::toDTO)
                .collect(Collectors.toList());
    }
}
