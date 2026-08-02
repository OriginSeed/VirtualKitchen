package com.processVisualisation.virtualKitchen.controller;

import com.processVisualisation.virtualKitchen.ai.dto.AIResponse;
import com.processVisualisation.virtualKitchen.dto.AIChatRequestDTO;
import com.processVisualisation.virtualKitchen.dto.AIChatResponseDTO;
import com.processVisualisation.virtualKitchen.service.IAIService;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AIController {

    private final IAIService aiService;

    public AIController(IAIService aiService) {
        this.aiService = aiService;
    }

    @PostMapping("/chat")
    public AIChatResponseDTO chat(@Valid @RequestBody AIChatRequestDTO request) {
        AIResponse response = aiService.chat(request.getPrompt());
        return new AIChatResponseDTO(response.getContent());
    }
}
