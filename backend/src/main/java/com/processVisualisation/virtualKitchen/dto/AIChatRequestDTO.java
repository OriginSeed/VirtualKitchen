package com.processVisualisation.virtualKitchen.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AIChatRequestDTO {

    @NotBlank(message = "prompt is required")
    private String prompt;
}
