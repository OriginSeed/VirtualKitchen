package com.processVisualisation.virtualKitchen.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Admin runtime config: stores per-section overrides in Mongo, layered over application properties, and applies
 * them to the live beans without a restart. Overrides are re-applied at startup.
 * <p>
 * Every write is checked against the section's field catalog (only the offered dropdown values are accepted), then
 * applied, and only stored once applying succeeded, so a rejected change never reaches the database.
 */
@Slf4j
@Service
public class RuntimeConfigService {

    private final Map<String, ConfigSection> sections;
    private final RuntimeConfigOverridesRepository overridesRepository;
    private final AdminConfigAuditRepository auditRepository;

    public RuntimeConfigService(List<ConfigSection> sections,
                                RuntimeConfigOverridesRepository overridesRepository,
                                AdminConfigAuditRepository auditRepository) {
        this.sections = sections.stream()
                .collect(Collectors.toMap(ConfigSection::id, Function.identity(), (a, b) -> {
                    throw new IllegalStateException("Duplicate config section id " + a.id());
                }, LinkedHashMap::new));
        this.overridesRepository = overridesRepository;
        this.auditRepository = auditRepository;
    }

    /** Re-applies stored overrides at startup. A section whose overrides no longer apply keeps its baseline. */
    @EventListener(ApplicationReadyEvent.class)
    public void applyStoredOverrides() {
        for (ConfigSection section : sections.values()) {
            if (!section.available()) {
                continue;
            }
            Map<String, String> overrides = storedOverrides(section);
            if (overrides.isEmpty() && !section.applyAtStartup()) {
                continue;
            }
            try {
                section.apply(overrides);
                log.info("Applied {} runtime config override(s) to section '{}'", overrides.size(), section.id());
            } catch (RuntimeException e) {
                log.warn("Stored runtime config for section '{}' no longer applies; using the baseline: {}",
                        section.id(), e.getMessage());
            }
        }
    }

    public List<SectionView> list() {
        return sections.values().stream().map(this::view).toList();
    }

    public SectionView get(String sectionId) {
        return view(require(sectionId));
    }

    /**
     * Applies {@code changes} on top of the section's current overrides. A value equal to the field's baseline
     * removes that override.
     *
     * @throws IllegalArgumentException for an unknown section/field, a value that is not offered, or an invalid
     *         combination (the live config and the stored overrides are then unchanged)
     */
    public SectionView update(String sectionId, Map<String, String> changes, Long adminId) {
        ConfigSection section = requireAvailable(sectionId);
        Map<String, String> current = storedOverrides(section);
        Map<String, ConfigField> fields = fieldsByKey(section, current);

        Map<String, String> next = new LinkedHashMap<>(current);
        Map<String, String> before = new LinkedHashMap<>();
        Map<String, String> after = new LinkedHashMap<>();
        for (Map.Entry<String, String> change : changes.entrySet()) {
            ConfigField field = fields.get(change.getKey());
            if (field == null) {
                throw new IllegalArgumentException("Unknown setting '" + change.getKey() + "' in " + section.label());
            }
            String value = change.getValue() == null ? "" : change.getValue().trim();
            if (!field.accepts(value)) {
                throw new IllegalArgumentException("'" + value + "' is not an allowed value for " + field.label());
            }
            boolean isBaseline = field.type() != ConfigField.Type.SECRET && value.equals(field.baseline());
            String previous = current.get(field.key());
            if (isBaseline) {
                next.remove(field.key());
            } else {
                next.put(field.key(), value);
            }
            if (!Objects.equals(previous, next.get(field.key()))) {
                before.put(field.key(), previous == null ? "" : section.forAudit(field.key(), previous));
                after.put(field.key(), isBaseline ? "" : section.forAudit(field.key(), value));
            }
        }
        if (after.isEmpty()) {
            return view(section);
        }

        section.apply(next);
        save(section, next, adminId);
        audit(section, "UPDATE", adminId, before, after);
        return view(section);
    }

    /** Drops every override in the section and re-applies the baseline. */
    public SectionView reset(String sectionId, Long adminId) {
        ConfigSection section = requireAvailable(sectionId);
        Map<String, String> current = storedOverrides(section);
        section.apply(Map.of());
        overridesRepository.deleteById(section.id());
        if (!current.isEmpty()) {
            Map<String, String> before = new LinkedHashMap<>();
            current.forEach((key, value) -> before.put(key, section.forAudit(key, value)));
            audit(section, "RESET", adminId, before, Map.of());
        }
        return view(section);
    }

    /**
     * Applies the section's stored overrides again, e.g. after something the section reads besides its overrides
     * changed (admin-added models).
     *
     * @throws IllegalArgumentException if they no longer apply; the live config is then unchanged
     */
    public void reapply(String sectionId) {
        ConfigSection section = requireAvailable(sectionId);
        section.apply(storedOverrides(section));
    }

    /** Drops the section's overrides whose key matches {@code keyFilter} (e.g. those of a deleted model). */
    public void dropOverrides(String sectionId, Predicate<String> keyFilter, Long adminId) {
        ConfigSection section = requireAvailable(sectionId);
        Map<String, String> current = storedOverrides(section);
        Map<String, String> next = new LinkedHashMap<>(current);
        next.keySet().removeIf(keyFilter);
        if (next.size() != current.size()) {
            save(section, next, adminId);
        }
    }

    /** Records an admin change made outside the field catalog (e.g. a model added or deleted). */
    public void audit(String sectionId, String action, Long adminId, Map<String, String> before, Map<String, String> after) {
        audit(require(sectionId), action, adminId, before, after);
    }

    private SectionView view(ConfigSection section) {
        Map<String, String> overrides = section.available() ? storedOverrides(section) : Map.of();
        return new SectionView(section.id(), section.label(), section.description(), section.available(),
                section.unavailableReason(), section.fields(overrides));
    }

    /** The section's stored overrides, decoded for {@link ConfigSection#apply}. */
    private Map<String, String> storedOverrides(ConfigSection section) {
        Optional<RuntimeConfigOverrides> stored = overridesRepository.findById(section.id());
        Map<String, String> decoded = new LinkedHashMap<>();
        stored.ifPresent(doc -> doc.valueMap().forEach((key, value) -> decoded.put(key, section.fromStored(key, value))));
        return decoded;
    }

    private void save(ConfigSection section, Map<String, String> overrides, Long adminId) {
        if (overrides.isEmpty()) {
            overridesRepository.deleteById(section.id());
            return;
        }
        RuntimeConfigOverrides doc = overridesRepository.findById(section.id()).orElseGet(() -> {
            RuntimeConfigOverrides created = new RuntimeConfigOverrides();
            created.setSection(section.id());
            return created;
        });
        Map<String, String> encoded = new LinkedHashMap<>();
        overrides.forEach((key, value) -> encoded.put(key, section.toStored(key, value)));
        doc.putValues(encoded);
        doc.setVersion(doc.getVersion() + 1);
        doc.setUpdatedBy(adminId);
        doc.setUpdatedAt(LocalDateTime.now());
        overridesRepository.save(doc);
    }

    private void audit(ConfigSection section, String action, Long adminId,
                       Map<String, String> before, Map<String, String> after) {
        AdminConfigAudit entry = new AdminConfigAudit();
        entry.setSection(section.id());
        entry.setAction(action);
        entry.setAdminId(adminId);
        entry.setBefore(ConfigEntry.listOf(before));
        entry.setAfter(ConfigEntry.listOf(after));
        entry.setAt(LocalDateTime.now());
        auditRepository.save(entry);
    }

    private static Map<String, ConfigField> fieldsByKey(ConfigSection section, Map<String, String> overrides) {
        return section.fields(overrides).stream()
                .collect(Collectors.toMap(ConfigField::key, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    private ConfigSection require(String sectionId) {
        ConfigSection section = sections.get(sectionId);
        if (section == null) {
            throw new IllegalArgumentException("Unknown config section '" + sectionId + "'");
        }
        return section;
    }

    private ConfigSection requireAvailable(String sectionId) {
        ConfigSection section = require(sectionId);
        if (!section.available()) {
            throw new IllegalArgumentException(section.label() + " can't be edited: " + section.unavailableReason());
        }
        return section;
    }

    /** A section as the admin UI shows it. */
    public record SectionView(String id, String label, String description, boolean available,
                              String unavailableReason, List<ConfigField> fields) {
    }
}
