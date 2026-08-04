//package com.processVisualisation.virtualKitchen.service;
//
//import com.processVisualisation.virtualKitchen.ai.client.AIClient;
//import com.processVisualisation.virtualKitchen.ai.dto.AIRequest;
//import com.processVisualisation.virtualKitchen.ai.dto.AIResponse;
//import com.processVisualisation.virtualKitchen.dto.RecipeFlowGenerationResponseDTO;
//import com.processVisualisation.virtualKitchen.exception.RecipeFlowGenerationException;
//import com.processVisualisation.virtualKitchen.service.recipe.RecipeFlowPromptBuilder;
//import com.processVisualisation.virtualKitchen.service.recipe.RecipeValidator;
//import org.junit.jupiter.api.Test;
//
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.junit.jupiter.api.Assertions.assertThrows;
//
//class RecipeGenerationServiceTest {
//
//    @Test
//    void shouldRetryOnceAndReturnValidGraph() {
//        StubAIClient aiClient = new StubAIClient(
//                "{\"nodes\":[{\"id\":\"n1\",\"type\":\"unknown\",\"data\":{\"title\":\"bad\"}}],\"edges\":[]}",
//                "{\"nodes\":[{\"id\":\"n1\",\"type\":\"recipeStepNode\",\"data\":{\"title\":\"Wash\",\"step\":{}}},{\"id\":\"n2\",\"type\":\"recipeStepNode\",\"data\":{\"title\":\"Cook\",\"step\":{}}}],\"edges\":[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"}]}"
//        );
//
//        RecipeGenerationService service = new RecipeGenerationService(
//                aiClient,
//                new RecipeFlowPromptBuilder(),
//                new RecipeValidator()
//        );
//
//        RecipeFlowGenerationResponseDTO response = service.generateFlow("Wash and cook rice");
//
//        assertEquals(2, response.getNodes().size());
//        assertEquals(1, response.getEdges().size());
//        assertEquals(2, aiClient.calls);
//    }
//
//    @Test
//    void shouldFailAfterRetryIfStillInvalid() {
//        StubAIClient aiClient = new StubAIClient(
//                "{\"nodes\":[],\"edges\":[{\"id\":\"e1\",\"source\":\"missing\",\"target\":\"missing\"}]}",
//                "{\"nodes\":[],\"edges\":[{\"id\":\"e2\",\"source\":\"missing\",\"target\":\"missing\"}]}"
//        );
//
//        RecipeGenerationService service = new RecipeGenerationService(
//                aiClient,
//                new RecipeFlowPromptBuilder(),
//                new RecipeValidator()
//        );
//
//        assertThrows(RecipeFlowGenerationException.class, () -> service.generateFlow("Invalid"));
//        assertEquals(2, aiClient.calls);
//    }
//
//    private static class StubAIClient implements AIClient {
//        private final String first;
//        private final String second;
//        private int calls;
//
//        private StubAIClient(String first, String second) {
//            this.first = first;
//            this.second = second;
//        }
//
//        @Override
//        public AIResponse chat(AIRequest request) {
//            calls++;
//            String content = calls == 1 ? first : second;
//            return AIResponse.builder()
//                    .content(content)
//                    .model("test")
//                    .build();
//        }
//    }
//}
