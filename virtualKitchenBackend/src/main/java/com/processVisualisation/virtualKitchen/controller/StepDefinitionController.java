package com.processVisualisation.virtualKitchen.controller;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.service.IStepDefinitionService;
import com.processVisualisation.virtualKitchen.utils.ApiResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/step-definitions")
public class StepDefinitionController {

    @Autowired
    private IStepDefinitionService service;

    @PostMapping
    public ApiResponse<StepDefinitionResponseDTO> create(@RequestBody StepDefinitionRequestDTO dto){
        return build(service.create(dto), "created");
    }

    @GetMapping
    public ApiResponse<List<StepDefinitionResponseDTO>> getAll(){
        return build(service.getAll(), "fetched");
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
