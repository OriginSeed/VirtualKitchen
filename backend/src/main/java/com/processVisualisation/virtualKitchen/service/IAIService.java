package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.ai.dto.AIResponse;

public interface IAIService {

    AIResponse chat(String prompt);
}
