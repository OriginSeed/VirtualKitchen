package com.processVisualisation.virtualKitchen.ai.platform.preference;

import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.auth.security.CurrentUser;
import com.processVisualisation.virtualKitchen.common.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The current user's own AI model choices (Account Settings). Credits are charged at the chosen model's cost.
 * Only text and image models can be chosen; narration always uses the admin's default.
 */
@RestController
@RequestMapping("/api/v1/users/me/ai-preferences")
public class AiPreferenceController {

    static final List<AiCapability> CHOOSABLE = List.of(AiCapability.TEXT_TO_TEXT, AiCapability.TEXT_TO_IMAGE);

    private final PreferredModelResolver resolver;

    public AiPreferenceController(PreferredModelResolver resolver) {
        this.resolver = resolver;
    }

    @GetMapping
    public ApiResponse<PreferencesView> get() {
        return ok("AI model preferences", view(CurrentUser.requireUserId()));
    }

    /** Body: {@code {"modelKeys": {"TEXT_TO_TEXT": "<model key or null>", "TEXT_TO_IMAGE": ...}}}. */
    @PutMapping
    public ApiResponse<PreferencesView> update(@RequestBody UpdateRequest request) {
        Long userId = CurrentUser.requireUserId();
        Map<AiCapability, String> choices = new EnumMap<>(AiCapability.class);
        if (request.modelKeys() != null) {
            request.modelKeys().forEach((capability, key) -> {
                AiCapability parsed = AiCapability.valueOf(capability);
                if (!CHOOSABLE.contains(parsed)) {
                    throw new IllegalArgumentException(capability + " models can't be chosen");
                }
                choices.put(parsed, key);
            });
        }
        resolver.save(userId, choices);
        return ok("AI model preferences saved", view(userId));
    }

    private PreferencesView view(Long userId) {
        Map<String, String> saved = resolver.saved(userId);
        Map<String, String> selected = new LinkedHashMap<>();
        Map<String, String> effective = new LinkedHashMap<>();
        for (AiCapability capability : CHOOSABLE) {
            // A stale saved choice reads as "no choice": that is what requests will do.
            selected.put(capability.name(), resolver.preferred(userId, capability).map(m -> m.getKey()).orElse(null));
            String effectiveKey;
            try {
                effectiveKey = resolver.resolve(userId, capability).getKey();
            } catch (IllegalStateException e) {
                effectiveKey = null;
            }
            effective.put(capability.name(), effectiveKey);
        }
        boolean staleChoice = CHOOSABLE.stream()
                .anyMatch(c -> saved.get(c.name()) != null && selected.get(c.name()) == null);
        return new PreferencesView(selected, effective, staleChoice);
    }

    public record UpdateRequest(Map<String, String> modelKeys) {
    }

    /**
     * @param modelKeys  the user's usable choice per capability (null = default)
     * @param effective  the model each capability's requests run on now
     * @param staleChoice a saved choice is no longer offered, so the default applies instead
     */
    public record PreferencesView(Map<String, String> modelKeys, Map<String, String> effective, boolean staleChoice) {
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
