package com.processVisualisation.virtualKitchen.auth.security;

import com.processVisualisation.virtualKitchen.ai.recipe.narration.controller.NarrationAudioController;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.storage.FileSystemNarrationAudioStorage;
import com.processVisualisation.virtualKitchen.auth.controller.AuthController;
import com.processVisualisation.virtualKitchen.auth.controller.UserController;
import com.processVisualisation.virtualKitchen.auth.service.AuthService;
import com.processVisualisation.virtualKitchen.auth.service.UserService;
import com.processVisualisation.virtualKitchen.kitchen.controller.InventoryController;
import com.processVisualisation.virtualKitchen.kitchen.controller.KitchenController;
import com.processVisualisation.virtualKitchen.kitchen.model.Kitchen;
import com.processVisualisation.virtualKitchen.kitchen.repository.KitchenRepository;
import com.processVisualisation.virtualKitchen.kitchen.service.InventoryService;
import com.processVisualisation.virtualKitchen.kitchen.service.KitchenAccess;
import com.processVisualisation.virtualKitchen.kitchen.service.KitchenService;
import com.processVisualisation.virtualKitchen.store.controller.IngredientController;
import com.processVisualisation.virtualKitchen.store.service.IngredientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The application-wide rules in {@link SecurityConfig}: only sign-in and narration audio are open, the API needs
 * a JWT, back-office endpoints need an ADMIN, and a user's kitchen and pantry are theirs alone.
 */
@WebMvcTest({AuthController.class, NarrationAudioController.class, IngredientController.class,
        UserController.class, KitchenController.class, InventoryController.class})
@Import({SecurityConfig.class, KitchenAccess.class})
class SecurityRulesTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private FileSystemNarrationAudioStorage narrationStorage;
    @MockitoBean
    private IngredientService ingredientService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private KitchenService kitchenService;
    @MockitoBean
    private InventoryService inventoryService;
    @MockitoBean
    private KitchenRepository kitchenRepository;

    /** The request got past security (whatever the controller then answered). */
    private static final ResultMatcher NOT_BLOCKED = result -> {
        int code = result.getResponse().getStatus();
        assertTrue(code != 401 && code != 403, "expected the request to pass security, got " + code);
    };

    @Test
    void signInAndNarrationAudioWorkSignedOut() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"cook\",\"password\":\"secret-pass\"}"))
                .andExpect(NOT_BLOCKED);
        when(narrationStorage.resolve("missing.wav")).thenReturn(Optional.empty());
        // Unknown file: a 404 from the controller, not a 401 from security.
        mockMvc.perform(get("/api/v1/narration-audio/missing.wav")).andExpect(status().isNotFound());
    }

    @Test
    void whoAmIAndTheRestOfTheApiNeedAToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/ingredients")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/kitchens/owner/7")).andExpect(status().isUnauthorized());
        verifyNoInteractions(ingredientService, kitchenService);
    }

    @Test
    void signedInUsersCanReadTheCatalogButOnlyAdminsChangeIt() throws Exception {
        mockMvc.perform(get("/api/v1/ingredients").with(user(7L))).andExpect(NOT_BLOCKED);
        mockMvc.perform(post("/api/v1/ingredients").with(user(7L)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/ingredients/1").with(user(7L))).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/ingredients/1").with(admin(1L))).andExpect(NOT_BLOCKED);
    }

    @Test
    void userManagementIsForAdmins() throws Exception {
        mockMvc.perform(get("/api/v1/users/8").with(user(7L))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/users/8").with(admin(1L))).andExpect(NOT_BLOCKED);
    }

    @Test
    void aKitchenAndItsPantryBelongToTheirOwner() throws Exception {
        Kitchen kitchen = new Kitchen();
        kitchen.setId(3L);
        kitchen.setOwnerId(7L);
        when(kitchenRepository.findById(3L)).thenReturn(Optional.of(kitchen));
        when(kitchenService.getByOwner(7L)).thenReturn(List.of());
        when(inventoryService.getByKitchen(3L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/kitchens/owner/7").with(user(7L))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/kitchens/owner/7").with(user(8L))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/inventory/kitchen/3").with(user(7L))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/inventory/kitchen/3").with(user(8L))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/inventory/kitchen/3").with(admin(1L))).andExpect(status().isOk());
        // Stock changes only come from orders (or an admin), never straight from a client.
        mockMvc.perform(post("/api/v1/inventory").with(user(7L)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor user(Long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static RequestPostProcessor admin(Long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
}
