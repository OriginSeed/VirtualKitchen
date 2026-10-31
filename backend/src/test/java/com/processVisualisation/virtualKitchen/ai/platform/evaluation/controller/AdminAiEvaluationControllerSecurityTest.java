package com.processVisualisation.virtualKitchen.ai.platform.evaluation.controller;

import com.processVisualisation.virtualKitchen.ai.platform.analytics.query.AnalyticsQueryService;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationQueryService;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationService;
import com.processVisualisation.virtualKitchen.auth.security.JwtService;
import com.processVisualisation.virtualKitchen.auth.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The evaluation API through the real {@link SecurityConfig} chain: admins only, enforced on the server. */
@WebMvcTest(AdminAiEvaluationController.class)
@Import(SecurityConfig.class)
class AdminAiEvaluationControllerSecurityTest {

    private static final String BASE = "/api/v1/admin/ai-evaluation";
    private static final String SCORE_BODY = "{\"consistency\":4,\"defectTags\":[],\"revision\":null}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EvaluationQueryService queryService;
    @MockitoBean
    private EvaluationService evaluationService;
    @MockitoBean
    private JwtService jwtService;

    @Test
    void adminCanReadTheRubricAndListSets() throws Exception {
        when(queryService.listSets(any(), eq("unscored"), anyInt(), anyInt())).thenReturn(
                new EvaluationQueryService.SetPage(new AnalyticsQueryService.Page<>(List.of(), 0, 0, 25), 0, false));

        mockMvc.perform(get(BASE + "/rubric").with(user(1L, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("v1"));
        mockMvc.perform(get(BASE + "/sets").param("model", "gemini-image").with(user(1L, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.total").value(0));
    }

    @Test
    void anUnknownSetIs404() throws Exception {
        when(queryService.setDetail(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get(BASE + "/sets/7::wf1").with(user(1L, "ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aStaleEditIs409_andInvalidInputIs400() throws Exception {
        when(evaluationService.saveImage(eq(1L), eq("7::wf1"), eq("r1"), any()))
                .thenThrow(new EvaluationService.ConflictException("changed meanwhile"));
        when(evaluationService.saveImage(eq(1L), eq("7::wf1"), eq("r2"), any()))
                .thenThrow(new IllegalArgumentException("consistency must be between 1 and 5"));

        mockMvc.perform(put(BASE + "/sets/7::wf1/images/r1").with(user(1L, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(SCORE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("changed meanwhile"));
        mockMvc.perform(put(BASE + "/sets/7::wf1/images/r2").with(user(1L, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(SCORE_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonAdminIsForbidden_andAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(BASE + "/compare").with(user(2L, "USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(BASE + "/sets/7::wf1/verdict").with(user(2L, "USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\":\"ACCEPTED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/sets"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(queryService, evaluationService);
    }

    private static RequestPostProcessor user(Long id, String userType) {
        return authentication(new UsernamePasswordAuthenticationToken(id, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + userType))));
    }
}
