package com.processVisualisation.virtualKitchen.store.controller;

import jakarta.validation.Valid;
import com.processVisualisation.virtualKitchen.common.web.ApiResponse;
import com.processVisualisation.virtualKitchen.store.dto.EquipmentRequestDTO;
import com.processVisualisation.virtualKitchen.store.dto.EquipmentResponseDTO;
import com.processVisualisation.virtualKitchen.store.service.EquipmentService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * REST controller exposing endpoints for the equipment catalog under
 * {@code /api/v1/equipments}. Delegates persistence and business logic to
 * {@link EquipmentService} and wraps every response in an {@link ApiResponse}.
 */
@RestController
@RequestMapping("/api/v1/equipments")
public class EquipmentController {

    @Autowired
    private EquipmentService service;

    /**
     * Creates a new equipment catalog entry.
     *
     * @param dto the equipment name/description to create
     * @return an {@link ApiResponse} wrapping the created equipment
     * @throws RuntimeException if equipment with the same name already exists
     */
    @PostMapping
    public ApiResponse<EquipmentResponseDTO> create(@Valid @RequestBody EquipmentRequestDTO dto){
        return build(service.create(dto), "created");
    }

    /**
     * Retrieves every equipment catalog entry.
     *
     * @return an {@link ApiResponse} wrapping the list of all equipment
     */
    @GetMapping
    public ApiResponse<List<EquipmentResponseDTO>> getAll(){
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
