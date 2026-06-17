package com.processVisualisation.virtualKitchen.controller;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.service.IStepExecutionService;
import com.processVisualisation.virtualKitchen.utils.ApiResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/step-executions")
public class StepExecutionController {

    @Autowired
    private IStepExecutionService service;

    @PostMapping
    public ApiResponse<StepExecutionResponseDTO> create(@RequestBody StepExecutionRequestDTO dto){
        return build(service.create(dto), "created");
    }

    @PutMapping("/{id}")
    public ApiResponse<StepExecutionResponseDTO> update(@PathVariable Long id,
                                                        @RequestBody StepExecutionUpdateDTO dto){
        return build(service.update(id, dto), "updated");
    }

    @GetMapping("/{processId}")
    public ApiResponse<List<StepExecutionResponseDTO>> get(@PathVariable Long processId){
        return build(service.getByProcess(processId), "fetched");
    }

    private <T> ApiResponse<T> build(T data, String msg){
        return ApiResponse.<T>builder()
                .success(true)
                .message(msg)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
