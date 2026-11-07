package com.processVisualisation.virtualKitchen.ai.platform.preference;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A user's own AI model choices, one per capability. A choice is only honoured while the model is still enabled and
 * user-selectable (see {@link PreferredModelResolver}); otherwise the admin's default applies.
 */
@Data
@Document(collection = "user_ai_preferences")
public class UserAiPreferences {

    @Id
    private Long userId;

    /** Registry model key keyed by {@code AiCapability#name()}; a missing entry means "use the default". */
    private Map<String, String> modelKeys = new LinkedHashMap<>();

    private LocalDateTime updatedAt;
}
