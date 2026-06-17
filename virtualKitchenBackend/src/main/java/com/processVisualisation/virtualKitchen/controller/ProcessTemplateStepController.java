package com.processVisualisation.virtualKitchen.controller;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.service.IProcessTemplateStepService;
import com.processVisualisation.virtualKitchen.utils.ApiResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/process-template-steps")
public class ProcessTemplateStepController {

    @Autowired
    private IProcessTemplateStepService service;

    @PostMapping
    public ApiResponse<ProcessTemplateStepResponseDTO> create(@RequestBody ProcessTemplateStepRequestDTO dto){
        return build(service.create(dto), "created");
    }

    @GetMapping("/{templateId}")
    public ApiResponse<List<ProcessTemplateStepResponseDTO>> get(@PathVariable Long templateId){
        return build(service.getSteps(templateId), "fetched");
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
