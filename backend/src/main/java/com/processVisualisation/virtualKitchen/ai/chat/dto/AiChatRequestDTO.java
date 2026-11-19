package com.processVisualisation.virtualKitchen.ai.chat.dto;

import jakarta.validation.constraints.Size;
import com.processVisualisation.virtualKitchen.common.web.InputLimits;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for the AI chat endpoint, carrying the free-form prompt
 * text a client wants the AI service to respond to.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiChatRequestDTO {

    @NotBlank(message = "prompt is required")
    @Size(max = InputLimits.CHAT_PROMPT, message = "Prompt can be at most " + InputLimits.CHAT_PROMPT + " characters")
    private String prompt;
}
