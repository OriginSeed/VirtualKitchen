package com.processVisualisation.virtualKitchen.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class KitchenUpdateDTO {

    @NotBlank
    private String name;
}
