package com.processVisualisation.virtualKitchen.admin.config;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

/** One admin change to a runtime config section. Secret values are recorded masked, never in clear. */
@Data
@Document(collection = "admin_config_audit")
public class AdminConfigAudit {

    @Id
    private String id;

    private String section;

    /** {@code UPDATE} or {@code RESET}. */
    private String action;

    private Long adminId;

    /** Changed keys only: their previous and new effective values ("" for the baseline). */
    private Map<String, String> before;
    private Map<String, String> after;

    private LocalDateTime at;
}
