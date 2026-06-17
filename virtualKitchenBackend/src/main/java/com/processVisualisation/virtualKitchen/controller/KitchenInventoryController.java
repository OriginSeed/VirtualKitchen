package com.processVisualisation.virtualKitchen.controller;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.service.IKitchenInventoryService;
import com.processVisualisation.virtualKitchen.utils.ApiResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/kitchen-inventory")
public class KitchenInventoryController {

    @Autowired
    private IKitchenInventoryService service;

    @PostMapping
    public ApiResponse<KitchenInventoryResponseDTO> create(@RequestBody KitchenInventoryRequestDTO dto){
        return build(service.create(dto), "created");
    }

    @GetMapping("/{kitchenId}")
    public ApiResponse<List<KitchenInventoryResponseDTO>> get(@PathVariable Long kitchenId){
        return build(service.getByKitchen(kitchenId), "fetched");
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
