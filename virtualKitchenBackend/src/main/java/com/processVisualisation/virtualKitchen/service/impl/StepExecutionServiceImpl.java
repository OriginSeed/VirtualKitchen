package com.processVisualisation.virtualKitchen.service.impl;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.mapper.StepExecutionMapper;
import com.processVisualisation.virtualKitchen.model.StepExecution;
import com.processVisualisation.virtualKitchen.model.StepStatus;
import com.processVisualisation.virtualKitchen.repository.StepExecutionRepository;
import com.processVisualisation.virtualKitchen.service.IStepExecutionService;
import com.processVisualisation.virtualKitchen.service.SequenceGeneratorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class StepExecutionServiceImpl implements IStepExecutionService {

    @Autowired
    private StepExecutionRepository repo;

    @Autowired
    private StepExecutionMapper mapper;

    @Autowired
    private SequenceGeneratorService seq;

    @Override
    public StepExecutionResponseDTO create(StepExecutionRequestDTO dto){
        StepExecution step = mapper.toEntity(dto);
        step.setId(seq.generateSequence(StepExecution.SEQUENCE_NAME));
        return mapper.toDTO(repo.save(step));
    }

    @Override
    public StepExecutionResponseDTO update(Long id, StepExecutionUpdateDTO dto){
        StepExecution step = repo.findById(id).orElseThrow();

        StepStatus newStatus = StepStatus.valueOf(dto.getStatus());
        step.setStatus(newStatus);

        if(newStatus == StepStatus.IN_PROGRESS){
            step.setStartedAt(LocalDateTime.now());
        }

        if(newStatus == StepStatus.DONE){
            step.setCompletedAt(LocalDateTime.now());
        }

        step.setNotes(dto.getNotes());

        return mapper.toDTO(repo.save(step));
    }

    @Override
    public List<StepExecutionResponseDTO> getByProcess(Long processExecutionId){
        return repo.findByProcessExecutionIdOrderByIdAsc(processExecutionId)
                .stream()
                .map(mapper::toDTO)
                .collect(Collectors.toList());
    }
}
