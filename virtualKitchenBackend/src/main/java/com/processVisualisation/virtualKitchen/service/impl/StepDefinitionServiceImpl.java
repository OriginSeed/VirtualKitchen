package com.processVisualisation.virtualKitchen.service.impl;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.mapper.StepDefinitionMapper;
import com.processVisualisation.virtualKitchen.model.StepDefinition;
import com.processVisualisation.virtualKitchen.repository.StepDefinitionRepository;
import com.processVisualisation.virtualKitchen.service.IStepDefinitionService;
import com.processVisualisation.virtualKitchen.service.SequenceGeneratorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class StepDefinitionServiceImpl implements IStepDefinitionService {

    @Autowired
    private StepDefinitionRepository repo;

    @Autowired
    private StepDefinitionMapper mapper;

    @Autowired
    private SequenceGeneratorService seq;

    @Override
    public StepDefinitionResponseDTO create(StepDefinitionRequestDTO dto){
        StepDefinition step = mapper.toEntity(dto);
        step.setId(seq.generateSequence(StepDefinition.SEQUENCE_NAME));
        return mapper.toDTO(repo.save(step));
    }

    @Override
    public List<StepDefinitionResponseDTO> getAll(){
        return repo.findAll()
                .stream()
                .map(mapper::toDTO)
                .collect(Collectors.toList());
    }
}
