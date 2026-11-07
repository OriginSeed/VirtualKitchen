package com.processVisualisation.virtualKitchen.admin.config;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The admin's runtime overrides for one {@link ConfigSection}, layered on top of application properties. Only keys
 * that differ from the baseline are stored; deleting the document resets the section.
 */
@Data
@Document(collection = "admin_runtime_config")
public class RuntimeConfigOverrides {

    /** The section id. */
    @Id
    private String section;

    /**
     * Field key to stored value (secrets are encrypted, see {@link ConfigSection#toStored}); a list, since the keys
     * contain dots (see {@link ConfigEntry}).
     */
    private List<ConfigEntry> values = new ArrayList<>();

    private long version;
    private Long updatedBy;
    private LocalDateTime updatedAt;

    public Map<String, String> valueMap() {
        return ConfigEntry.mapOf(values);
    }

    public void putValues(Map<String, String> values) {
        this.values = ConfigEntry.listOf(values);
    }
}
