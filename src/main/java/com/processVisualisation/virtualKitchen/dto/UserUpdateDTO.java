package com.processVisualisation.virtualKitchen.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserUpdateDTO {

    @NotBlank(message = "Name is required")
    private String name;
}