package com.processVisualisation.virtualKitchen.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class KitchenRequestDTO {

    @NotBlank
    private String name;

    @NotNull
    private Long ownerId;
}
