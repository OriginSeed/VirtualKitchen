package com.processVisualisation.virtualKitchen.admin.config;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

/** One admin change to a runtime config section. Secret values are recorded masked, never in clear. */
@Data
@Document(collection = "admin_config_audit")
public class AdminConfigAudit {

    @Id
    private String id;

    private String section;

    /** {@code UPDATE}, {@code RESET}, or {@code MODEL_ADD} / {@code MODEL_EDIT} / {@code MODEL_DELETE}. */
    private String action;

    private Long adminId;

    /** Changed keys only: their previous and new effective values ("" for the baseline); lists, as keys contain dots. */
    private List<ConfigEntry> before;
    private List<ConfigEntry> after;

    private LocalDateTime at;
}
