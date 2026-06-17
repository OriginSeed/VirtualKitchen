package com.processVisualisation.virtualKitchen.controller;

import com.processVisualisation.virtualKitchen.dto.*;
import com.processVisualisation.virtualKitchen.service.IIngredientService;
import com.processVisualisation.virtualKitchen.utils.ApiResponse;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/ingredients")
public class IngredientController {

    @Autowired
    private IIngredientService ingredientService;

    @PostMapping
    public ResponseEntity<ApiResponse<IngredientResponseDTO>> create(
            @Valid @RequestBody IngredientRequestDTO request) {

        return ResponseEntity.ok(
                buildResponse(true, "Ingredient created",
                        ingredientService.createIngredient(request))
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<IngredientResponseDTO>> getById(@PathVariable Long id) {

        return ResponseEntity.ok(
                buildResponse(true, "Ingredient fetched",
                        ingredientService.getIngredientById(id))
        );
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<IngredientResponseDTO>>> getAll() {

        return ResponseEntity.ok(
                buildResponse(true, "All ingredients fetched",
                        ingredientService.getAllIngredients())
        );
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<IngredientResponseDTO>> update(
            @PathVariable Long id,
            @Valid @RequestBody IngredientUpdateDTO request) {

        return ResponseEntity.ok(
                buildResponse(true, "Ingredient updated",
                        ingredientService.updateIngredient(id, request))
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {

        ingredientService.deleteIngredient(id);

        return ResponseEntity.ok(
                buildResponse(true, "Ingredient deleted", null)
        );
    }

    private <T> ApiResponse<T> buildResponse(boolean success, String message, T data) {
        return ApiResponse.<T>builder()
                .success(success)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }
}