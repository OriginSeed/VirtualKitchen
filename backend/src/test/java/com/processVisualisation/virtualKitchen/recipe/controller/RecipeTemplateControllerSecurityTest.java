package com.processVisualisation.virtualKitchen.recipe.controller;

import com.processVisualisation.virtualKitchen.auth.security.JwtService;
import com.processVisualisation.virtualKitchen.auth.security.SecurityConfig;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeTemplateRequestDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeTemplateResponseDTO;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException;
import com.processVisualisation.virtualKitchen.recipe.model.Visibility;
import com.processVisualisation.virtualKitchen.recipe.service.RecipeTemplateService;
import com.processVisualisation.virtualKitchen.recipe.service.RecipeThumbnailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recipe templates act for the user in the verified JWT: a {@code userId} sent by the client is ignored, other
 * people's recipes can't be listed or changed, and nothing works signed out.
 */
@WebMvcTest(RecipeTemplateController.class)
@Import(SecurityConfig.class)
class RecipeTemplateControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecipeTemplateService service;

    @MockitoBean
    private RecipeThumbnailService thumbnailService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void signedOutCallersGetNothing() throws Exception {
        mockMvc.perform(get("/api/v1/process-templates/user/7")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/process-templates/global/7")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/process-templates/11")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/process-templates").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Soup\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/process-templates/11/visibility").param("userId", "7").param("visibility", "PUBLIC"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/process-templates/11").param("userId", "7")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void ownerListsTheirRecipesButNotSomeoneElses() throws Exception {
        when(service.getByUser(7L)).thenReturn(List.of());
        when(thumbnailService.resolveAll(anyList())).thenReturn(Map.of());

        mockMvc.perform(get("/api/v1/process-templates/user/7").with(user(7L))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/process-templates/user/8").with(user(7L))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/process-templates/global/8").with(user(7L))).andExpect(status().isForbidden());
    }

    @Test
    void discoverExcludesTheCallersOwnRecipes() throws Exception {
        when(service.getGlobalRecipes(7L)).thenReturn(List.of());
        when(thumbnailService.resolveAll(anyList())).thenReturn(Map.of());

        mockMvc.perform(get("/api/v1/process-templates/global/7").with(user(7L))).andExpect(status().isOk());
        verify(service).getGlobalRecipes(7L);
    }

    @Test
    void aNewRecipeAlwaysBelongsToTheCaller() throws Exception {
        when(service.create(any())).thenReturn(RecipeTemplateResponseDTO.builder().build());

        mockMvc.perform(post("/api/v1/process-templates").with(user(7L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Soup\",\"createdBy\":99}"))
                .andExpect(status().isOk());

        ArgumentCaptor<RecipeTemplateRequestDTO> request = ArgumentCaptor.forClass(RecipeTemplateRequestDTO.class);
        verify(service).create(request.capture());
        assertEquals(7L, request.getValue().getCreatedBy());
    }

    @Test
    void changesUseTheCallerNotAClientSuppliedUserId() throws Exception {
        when(service.updateVisibility(11L, 7L, Visibility.PUBLIC)).thenReturn(RecipeTemplateResponseDTO.builder().build());
        when(service.copyToUser(21L, 7L)).thenReturn(RecipeTemplateResponseDTO.builder().build());

        mockMvc.perform(put("/api/v1/process-templates/11/visibility").with(user(7L))
                        .param("userId", "99").param("visibility", "PUBLIC"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/process-templates/21/copy").with(user(7L)).param("userId", "99"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/process-templates/11").with(user(7L)).param("userId", "99"))
                .andExpect(status().isOk());

        verify(service).updateVisibility(11L, 7L, Visibility.PUBLIC);
        verify(service).copyToUser(21L, 7L);
        verify(service).delete(11L, 7L);
    }

    @Test
    void someoneElsesRecipeCannotBeChangedOrReadWhilePrivate() throws Exception {
        when(service.update(eq(11L), eq(8L), any())).thenThrow(new RecipeAccessDeniedException("You do not have permission to modify this recipe"));
        when(service.get(11L, 8L)).thenThrow(new RecipeAccessDeniedException("You do not have permission to view this recipe"));

        mockMvc.perform(put("/api/v1/process-templates/11").with(user(8L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Mine now\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/process-templates/11").with(user(8L))).andExpect(status().isForbidden());
    }

    private static RequestPostProcessor user(Long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
