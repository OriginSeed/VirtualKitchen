package com.processVisualisation.virtualKitchen.controller;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.service.IInventoryService;
import com.processVisualisation.virtualKitchen.utils.ApiResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    @Autowired
    private IInventoryService service;

    @PostMapping
    public ApiResponse<InventoryResponseDTO> addOrUpdate(@RequestBody InventoryRequestDTO dto){
        return build(service.addOrUpdate(dto), "updated");
    }

    @GetMapping("/{userId}")
    public ApiResponse<List<InventoryResponseDTO>> get(@PathVariable Long userId){
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
