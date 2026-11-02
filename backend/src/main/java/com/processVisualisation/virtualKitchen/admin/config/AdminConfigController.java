package com.processVisualisation.virtualKitchen.admin.config;

import com.processVisualisation.virtualKitchen.auth.security.CurrentUser;
import com.processVisualisation.virtualKitchen.common.web.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Admin API for runtime config (models, credits, thread pools, generation params, image profiles, mail…). Changes
 * apply live and survive restarts; resetting a section restores application properties.
 * <p>
 * Admin-only twice over: the {@code /api/v1/admin/**} URL rule requires the ADMIN role, and every handler calls
 * {@link CurrentUser#requireAdmin()}.
 */
@RestController
@RequestMapping("/api/v1/admin/config")
public class AdminConfigController {

    private final RuntimeConfigService configService;

    public AdminConfigController(RuntimeConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public ApiResponse<List<RuntimeConfigService.SectionView>> list() {
        CurrentUser.requireAdmin();
        return ok("Runtime config", configService.list());
    }

    @GetMapping("/{section}")
    public ApiResponse<RuntimeConfigService.SectionView> get(@PathVariable String section) {
        CurrentUser.requireAdmin();
        return ok("Runtime config section", configService.get(section));
    }

    /** Body: {@code {"values": {"<field key>": "<value>"}}}. Only the changed fields need to be sent. */
    @PutMapping("/{section}")
    public ApiResponse<RuntimeConfigService.SectionView> update(@PathVariable String section,
                                                                @RequestBody UpdateRequest request) {
        Long adminId = CurrentUser.requireAdmin();
        Map<String, String> values = request.values() == null ? Map.of() : request.values();
        return ok("Runtime config updated", configService.update(section, values, adminId));
    }

    @DeleteMapping("/{section}")
    public ApiResponse<RuntimeConfigService.SectionView> reset(@PathVariable String section) {
        Long adminId = CurrentUser.requireAdmin();
        return ok("Runtime config reset to defaults", configService.reset(section, adminId));
    }

    public record UpdateRequest(Map<String, String> values) {
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
