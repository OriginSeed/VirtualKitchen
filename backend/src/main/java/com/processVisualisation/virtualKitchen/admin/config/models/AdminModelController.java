package com.processVisualisation.virtualKitchen.admin.config.models;

import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.auth.security.CurrentUser;
import com.processVisualisation.virtualKitchen.common.web.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin API for AI models added from the admin panel. Admin-only twice over, as {@code AdminConfigController}: the
 * {@code /api/v1/admin/**} URL rule requires the ADMIN role, and every handler calls {@link CurrentUser#requireAdmin()}.
 */
@RestController
@RequestMapping("/api/v1/admin/config/models/catalog")
public class AdminModelController {

    private final AdminModelService service;
    private final ProviderModelCatalog catalog;

    public AdminModelController(AdminModelService service, ProviderModelCatalog catalog) {
        this.service = service;
        this.catalog = catalog;
    }

    /** The models a provider offers for a capability, for the model id dropdown; says why when it can't list them. */
    @GetMapping("/provider-models")
    public ApiResponse<ProviderModelCatalog.Listing> providerModels(@RequestParam String providerBean,
                                                                    @RequestParam AiCapability capability) {
        CurrentUser.requireAdmin();
        if (!service.providerBeans(capability).contains(providerBean)) {
            throw new IllegalArgumentException("'" + providerBean + "' can't serve " + capability + " models");
        }
        return ok("Provider models", catalog.list(providerBean, capability));
    }

    @GetMapping("/options")
    public ApiResponse<AdminModelService.Options> options() {
        CurrentUser.requireAdmin();
        return ok("Add model options", service.options());
    }

    @GetMapping
    public ApiResponse<List<AdminModel>> list() {
        CurrentUser.requireAdmin();
        return ok("Models added from the admin panel", service.list());
    }

    @PostMapping
    public ApiResponse<AdminModel> add(@RequestBody AdminModelService.ModelRequest request) {
        Long adminId = CurrentUser.requireAdmin();
        return ok("Model added", service.add(request, adminId));
    }

    @PutMapping("/{key}")
    public ApiResponse<AdminModel> update(@PathVariable String key, @RequestBody AdminModelService.ModelRequest request) {
        Long adminId = CurrentUser.requireAdmin();
        return ok("Model updated", service.update(key, request, adminId));
    }

    @DeleteMapping("/{key}")
    public ApiResponse<Void> delete(@PathVariable String key) {
        Long adminId = CurrentUser.requireAdmin();
        service.delete(key, adminId);
        return ok("Model deleted", null);
    }

    private static <T> ApiResponse<T> ok(String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
