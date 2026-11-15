package com.processVisualisation.virtualKitchen.kitchen.controller;

import com.processVisualisation.virtualKitchen.auth.security.CurrentUser;
import com.processVisualisation.virtualKitchen.common.web.ApiResponse;
import com.processVisualisation.virtualKitchen.kitchen.dto.InventoryRequestDTO;
import com.processVisualisation.virtualKitchen.kitchen.dto.InventoryResponseDTO;
import com.processVisualisation.virtualKitchen.kitchen.service.InventoryService;
import com.processVisualisation.virtualKitchen.kitchen.service.KitchenAccess;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * REST controller exposing inventory management endpoints under
 * {@code /api/v1/inventory}. Supports adding/updating an inventory item and
 * retrieving inventory scoped either to a user or to a kitchen, delegating
 * all business logic to {@link InventoryService}.
 */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    @Autowired
    private InventoryService service;

    @Autowired
    private KitchenAccess kitchenAccess;

    /**
     * Adds a new inventory item or increments the quantity of a matching
     * existing one. Handles {@code POST /api/v1/inventory}.
     *
     * @param dto the item to add/update, including its user/kitchen scope,
     *            item type/id, quantity and unit
     * @return an {@link ApiResponse} wrapping the resulting inventory item
     */
    @PostMapping
    public ApiResponse<InventoryResponseDTO> addOrUpdate(@RequestBody InventoryRequestDTO dto){
        return build(service.addOrUpdate(dto), "updated");
    }

    /**
     * Fetches all inventory items owned by a given user. Handles
     * {@code GET /api/v1/inventory/{userId}}.
     *
     * @param userId id of the owning user
     * @return an {@link ApiResponse} wrapping the user's inventory items
     */
    @GetMapping("/{userId}")
    public ApiResponse<List<InventoryResponseDTO>> get(@PathVariable Long userId){
        CurrentUser.requireSelfOrAdmin(userId);
        return build(service.getByUser(userId), "fetched");
    }

    /**
     * Fetches all inventory items allocated to a given kitchen. Handles
     * {@code GET /api/v1/inventory/kitchen/{kitchenId}}.
     *
     * @param kitchenId id of the kitchen
     * @return an {@link ApiResponse} wrapping the kitchen's inventory items
     */
    @GetMapping("/kitchen/{kitchenId}")
    public ApiResponse<List<InventoryResponseDTO>> getByKitchen(@PathVariable Long kitchenId){
        kitchenAccess.requireAccess(kitchenId);
        return build(service.getByKitchen(kitchenId), "fetched");
    }

    /**
     * Builds a standard success {@link ApiResponse} envelope for the given
     * payload and message.
     *
     * @param data the response payload
     * @param msg  a short human-readable status message
     * @param <T>  the payload type
     * @return the assembled response envelope
     */
    private <T> ApiResponse<T> build(T data, String msg){
        return ApiResponse.<T>builder()
                .success(true)
                .message(msg)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
