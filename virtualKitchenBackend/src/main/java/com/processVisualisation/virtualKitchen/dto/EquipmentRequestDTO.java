package com.processVisualisation.virtualKitchen.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class EquipmentRequestDTO {
    @NotBlank
    private String name;
    private String description;
}
