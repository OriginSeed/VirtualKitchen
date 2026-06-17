package com.processVisualisation.virtualKitchen.controller;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.service.IKitchenService;
import com.processVisualisation.virtualKitchen.utils.ApiResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/kitchens")
public class KitchenController {

    @Autowired
    private IKitchenService service;

    @PostMapping
    public ApiResponse<KitchenResponseDTO> create(@RequestBody KitchenRequestDTO dto){
        return build(service.create(dto), "created");
    }

    @GetMapping("/{id}")
    public ApiResponse<KitchenResponseDTO> get(@PathVariable Long id){
        return build(service.get(id), "fetched");
    }

    @GetMapping("/owner/{ownerId}")
    public ApiResponse<List<KitchenResponseDTO>> getByOwner(@PathVariable Long ownerId){
        return build(service.getByOwner(ownerId), "fetched");
    }

    @PutMapping("/{id}")
    public ApiResponse<KitchenResponseDTO> update(@PathVariable Long id,
                                                  @RequestBody KitchenUpdateDTO dto){
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
