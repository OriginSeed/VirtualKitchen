package com.processVisualisation.virtualKitchen.controller;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.service.IProcessTemplateService;
import com.processVisualisation.virtualKitchen.utils.ApiResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/process-templates")
public class ProcessTemplateController {

    @Autowired
    private IProcessTemplateService service;

    @PostMapping
    public ApiResponse<ProcessTemplateResponseDTO> create(@RequestBody ProcessTemplateRequestDTO dto){
        return build(service.create(dto), "created");
    }

    @GetMapping("/{id}")
    public ApiResponse<ProcessTemplateResponseDTO> get(@PathVariable Long id){
        return build(service.get(id), "fetched");
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<List<ProcessTemplateResponseDTO>> getByUser(@PathVariable Long userId){
        return build(service.getByUser(userId), "fetched");
    }

    @PutMapping("/{id}")
    public ApiResponse<ProcessTemplateResponseDTO> update(@PathVariable Long id,
                                                         @RequestBody ProcessTemplateUpdateDTO dto){
        return build(service.update(id, dto), "updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id){
        service.delete(id);
        return build(null, "deleted");
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
