package com.processVisualisation.virtualKitchen.controller;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.service.IProcessExecutionService;
import com.processVisualisation.virtualKitchen.utils.ApiResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/process-executions")
public class ProcessExecutionController {

    @Autowired
    private IProcessExecutionService service;

    @PostMapping
    public ApiResponse<ProcessExecutionResponseDTO> start(@RequestBody ProcessExecutionRequestDTO dto){
        return build(service.start(dto), "started");
    }

    @PutMapping("/{id}/{status}")
    public ApiResponse<ProcessExecutionResponseDTO> updateStatus(@PathVariable Long id,
                                                                @PathVariable String status){
        return build(service.updateStatus(id, status), "updated");
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<List<ProcessExecutionResponseDTO>> get(@PathVariable Long userId){
        return build(service.getByUser(userId), "fetched");
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
