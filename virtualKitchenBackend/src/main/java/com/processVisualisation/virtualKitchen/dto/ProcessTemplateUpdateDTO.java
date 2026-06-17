package com.processVisualisation.virtualKitchen.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class ProcessTemplateUpdateDTO {

    @NotBlank
    private String name;

    private String description;
}
