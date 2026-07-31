package com.processVisualisation.virtualKitchen.ai.client;

import com.processVisualisation.virtualKitchen.ai.dto.AIRequest;
import com.processVisualisation.virtualKitchen.ai.dto.AIResponse;

public interface AIClient {

    AIResponse chat(AIRequest request);
}
