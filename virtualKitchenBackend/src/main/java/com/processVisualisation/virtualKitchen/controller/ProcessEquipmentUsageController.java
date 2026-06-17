package com.processVisualisation.virtualKitchen.controller;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.service.IProcessEquipmentUsageService;
import com.processVisualisation.virtualKitchen.utils.ApiResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/process-equipment-usage")
public class ProcessEquipmentUsageController {

    @Autowired
    private IProcessEquipmentUsageService service;

    @PostMapping
    public ApiResponse<ProcessEquipmentUsageResponseDTO> create(@RequestBody ProcessEquipmentUsageRequestDTO dto){
        return build(service.create(dto), "created");
    }

    @GetMapping("/{processId}")
    public ApiResponse<List<ProcessEquipmentUsageResponseDTO>> get(@PathVariable Long processId){
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
