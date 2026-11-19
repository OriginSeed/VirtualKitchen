package com.processVisualisation.virtualKitchen.common.web;

import com.processVisualisation.virtualKitchen.ai.platform.queue.AiQueueFullException;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.dto.RecipeProcessGenerationRequestDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end (standalone MockMvc, no database) checks of how failed requests are answered: validation limits on
 * AI request text, class-level validation errors, unreadable and oversized bodies, and the stable error codes.
 */
class ProductionHardeningWebTest {

    private MockMvc mockMvc;

    @RestController
    static class TestController {
        @PostMapping("/generate")
        String generate(@Valid @RequestBody RecipeProcessGenerationRequestDTO body) {
            return "ok";
        }

        @PostMapping("/class-level")
        String classLevel(@Valid @RequestBody ClassLevelOnly body) {
            return "ok";
        }

        @PostMapping("/echo")
        String echo(@RequestBody String body) {
            return "ok";
        }

        @PostMapping("/missing")
        String missing() {
            throw new NoSuchElementException("recipe 42 not found in collection recipes");
        }

        @PostMapping("/busy")
        String busy() {
            throw new AiQueueFullException(AiCapability.TEXT_TO_TEXT);
        }
    }

    static class ClassLevelOnly {
        public String a;

        @AssertTrue(message = "Fill in field a")
        public boolean isValid() {
            return a != null;
        }
    }

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .addFilters(new RequestSizeLimitFilter(1024))
                .build();
    }

    @Test
    void oversizedRecipeTextIsAFriendly400() throws Exception {
        String text = "a".repeat(InputLimits.RECIPE_TEXT + 1);
        RequestSizeLimitFilter noLimit = new RequestSizeLimitFilter(Long.MAX_VALUE);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validatorBean())
                .addFilters(noLimit)
                .build();

        mvc.perform(post("/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipeText\":\"" + text + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Recipe text can be at most 8000 characters"));
    }

    @Test
    void classLevelValidationErrorIsA400NotA500() throws Exception {
        mockMvc.perform(post("/class-level").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Fill in field a"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void malformedJsonGetsAFriendlyMessage() throws Exception {
        mockMvc.perform(post("/generate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.UNREADABLE_BODY));
    }

    @Test
    void bodyOverTheSizeLimitIs413() throws Exception {
        mockMvc.perform(post("/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipeText\":\"" + "b".repeat(2000) + "\"}"))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void notFoundHidesInternalDetail() throws Exception {
        mockMvc.perform(post("/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.NOT_FOUND));
    }

    @Test
    void fullAiQueueIsCodedAiBusy() throws Exception {
        mockMvc.perform(post("/busy"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AI_BUSY"));
    }

    private static LocalValidatorFactoryBean validatorBean() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
