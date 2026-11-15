package com.processVisualisation.virtualKitchen.kitchen.controller;

import com.processVisualisation.virtualKitchen.auth.security.CurrentUser;
import com.processVisualisation.virtualKitchen.common.web.ApiResponse;
import com.processVisualisation.virtualKitchen.kitchen.dto.KitchenRequestDTO;
import com.processVisualisation.virtualKitchen.kitchen.dto.KitchenResponseDTO;
import com.processVisualisation.virtualKitchen.kitchen.dto.KitchenUpdateDTO;
import com.processVisualisation.virtualKitchen.kitchen.service.KitchenAccess;
import com.processVisualisation.virtualKitchen.kitchen.service.KitchenService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * REST controller exposing CRUD endpoints for kitchens under
 * {@code /api/v1/kitchens}: creation, lookup by id or owner, update, and
 * deletion. Delegates all business logic to {@link KitchenService}.
 */
@RestController
@RequestMapping("/api/v1/kitchens")
public class KitchenController {

    @Autowired
    private KitchenService service;

    @Autowired
    private KitchenAccess kitchenAccess;

    /**
     * Creates a new kitchen. Handles {@code POST /api/v1/kitchens}.
     *
     * @param dto the kitchen's name and owner id
     * @return an {@link ApiResponse} wrapping the created kitchen
     */
    @PostMapping
    public ApiResponse<KitchenResponseDTO> create(@RequestBody KitchenRequestDTO dto){
        return build(service.create(dto), "created");
    }

    /**
     * Fetches a single kitchen by its id. Handles
     * {@code GET /api/v1/kitchens/{id}}.
     *
     * @param id the kitchen id
     * @return an {@link ApiResponse} wrapping the matching kitchen
     * @throws java.util.NoSuchElementException if no kitchen exists with the given id
     */
    @GetMapping("/{id}")
    public ApiResponse<KitchenResponseDTO> get(@PathVariable Long id){
        // Only the owner (or an admin) sees a kitchen.
        kitchenAccess.requireAccess(id);
        return build(service.get(id), "fetched");
    }

    /**
     * Fetches all kitchens owned by a given owner. Handles
     * {@code GET /api/v1/kitchens/owner/{ownerId}}.
     *
     * @param ownerId id of the owning user
     * @return an {@link ApiResponse} wrapping the owner's kitchens
     */
    @GetMapping("/owner/{ownerId}")
    public ApiResponse<List<KitchenResponseDTO>> getByOwner(@PathVariable Long ownerId){
        CurrentUser.requireSelfOrAdmin(ownerId);
        return build(service.getByOwner(ownerId), "fetched");
    }

    /**
     * Updates an existing kitchen's mutable fields (currently its name).
     * Handles {@code PUT /api/v1/kitchens/{id}}.
     *
     * @param id  id of the kitchen to update
     * @param dto the new field values
     * @return an {@link ApiResponse} wrapping the updated kitchen
     * @throws java.util.NoSuchElementException if no kitchen exists with the given id
     */
    @PutMapping("/{id}")
    public ApiResponse<KitchenResponseDTO> update(@PathVariable Long id,
                                                  @RequestBody KitchenUpdateDTO dto){
        return build(service.update(id, dto), "updated");
    }

    /**
     * Deletes a kitchen by id. Handles {@code DELETE /api/v1/kitchens/{id}}.
     *
     * @param id id of the kitchen to delete
     * @return an {@link ApiResponse} with no payload confirming the deletion
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id){
        service.delete(id);
        return build(null, "deleted");
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
