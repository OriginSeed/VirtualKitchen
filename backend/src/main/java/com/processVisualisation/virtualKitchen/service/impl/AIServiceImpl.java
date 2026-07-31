package com.processVisualisation.virtualKitchen.service.impl;

import com.processVisualisation.virtualKitchen.ai.client.AIClient;
import com.processVisualisation.virtualKitchen.ai.config.OpenAIProperties;
import com.processVisualisation.virtualKitchen.ai.dto.AIRequest;
import com.processVisualisation.virtualKitchen.ai.dto.AIResponse;
import com.processVisualisation.virtualKitchen.service.IAIService;

import org.springframework.stereotype.Service;

@Service
public class AIServiceImpl implements IAIService {

    private final AIClient aiClient;
    private final OpenAIProperties openAIProperties;

    public AIServiceImpl(AIClient aiClient, OpenAIProperties openAIProperties) {
        this.aiClient = aiClient;
        this.openAIProperties = openAIProperties;
    }

    @Override
    public AIResponse chat(String prompt) {
        AIRequest request = AIRequest.builder()
                .systemPrompt("You are a helpful assistant for Virtual Kitchen.")
                .userPrompt(prompt)
                .model(openAIProperties.getDefaultModel())
                .temperature(0.2d)
                .maxTokens(300)
                .build();

        return aiClient.chat(request);
    }
}
