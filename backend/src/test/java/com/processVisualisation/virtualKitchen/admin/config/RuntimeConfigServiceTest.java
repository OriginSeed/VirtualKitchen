package com.processVisualisation.virtualKitchen.admin.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Admin runtime config: only offered values are accepted, overrides are stored and applied, resets restore. */
class RuntimeConfigServiceTest {

    private final Map<String, RuntimeConfigOverrides> store = new HashMap<>();
    private final List<AdminConfigAudit> audits = new ArrayList<>();
    private RuntimeConfigOverridesRepository overrides;
    private AdminConfigAuditRepository auditRepository;
    private FakeSection section;
    private RuntimeConfigService service;

    @BeforeEach
    void setUp() {
        overrides = mock(RuntimeConfigOverridesRepository.class);
        when(overrides.findById(anyString())).thenAnswer(i -> Optional.ofNullable(store.get(i.<String>getArgument(0))));
        when(overrides.save(any())).thenAnswer(i -> {
            RuntimeConfigOverrides doc = i.getArgument(0);
            store.put(doc.getSection(), doc);
            return doc;
        });
        doAnswer(i -> store.remove(i.<String>getArgument(0))).when(overrides).deleteById(anyString());
        auditRepository = mock(AdminConfigAuditRepository.class);
        when(auditRepository.save(any())).thenAnswer(i -> {
            audits.add(i.getArgument(0));
            return i.getArgument(0);
        });
        section = new FakeSection();
        service = new RuntimeConfigService(List.of(section), overrides, auditRepository);
    }

    @Test
    void anOfferedValueIsAppliedStoredAndAudited() {
        service.update("fake", Map.of("threads", "8"), 1L);

        assertEquals(Map.of("threads", "8"), section.applied);
        assertEquals("8", store.get("fake").getValues().get("threads"));
        assertEquals(1, audits.size());
        assertEquals("", audits.get(0).getBefore().get("threads"), "no override before: the baseline");
        assertEquals("8", audits.get(0).getAfter().get("threads"));
    }

    @Test
    void aValueThatIsNotOfferedIsRejectedAndNothingChanges() {
        assertThrows(IllegalArgumentException.class, () -> service.update("fake", Map.of("threads", "7"), 1L));

        assertTrue(section.applied.isEmpty());
        assertFalse(store.containsKey("fake"));
        verify(auditRepository, never()).save(any());
    }

    @Test
    void anUnknownSettingIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.update("fake", Map.of("nope", "1"), 1L));
    }

    @Test
    void choosingTheBaselineRemovesTheOverride() {
        service.update("fake", Map.of("threads", "8"), 1L);
        service.update("fake", Map.of("threads", "4"), 1L);

        assertTrue(section.applied.isEmpty());
        assertFalse(store.containsKey("fake"));
    }

    @Test
    void aCombinationTheSectionRejectsIsNotStored() {
        section.rejectAll = true;

        assertThrows(IllegalArgumentException.class, () -> service.update("fake", Map.of("threads", "8"), 1L));
        assertFalse(store.containsKey("fake"));
    }

    @Test
    void resetRestoresTheBaseline() {
        service.update("fake", Map.of("threads", "8"), 1L);
        service.reset("fake", 1L);

        assertTrue(section.applied.isEmpty());
        assertFalse(store.containsKey("fake"));
        assertEquals("RESET", audits.get(audits.size() - 1).getAction());
    }

    @Test
    void storedOverridesAreReappliedAtStartup() {
        RuntimeConfigOverrides doc = new RuntimeConfigOverrides();
        doc.setSection("fake");
        doc.getValues().put("threads", "16");
        store.put("fake", doc);

        service.applyStoredOverrides();

        assertEquals(Map.of("threads", "16"), section.applied);
    }

    @Test
    void theViewShowsLiveAndBaselineValues() {
        service.update("fake", Map.of("threads", "8"), 1L);

        ConfigField field = service.get("fake").fields().get(0);
        assertEquals("4", field.baseline());
        assertEquals("8", field.current());
        assertTrue(field.overridden());
    }

    /** One select field, "threads", offered 1/4/8/16 with baseline 4. */
    private static final class FakeSection implements ConfigSection {
        Map<String, String> applied = new LinkedHashMap<>();
        boolean rejectAll;

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public String label() {
            return "Fake";
        }

        @Override
        public String description() {
            return "";
        }

        @Override
        public List<ConfigField> fields(Map<String, String> overrides) {
            return List.of(ConfigField.select("threads", "Threads", "Group", List.of("1", "4", "8", "16"), "4",
                    overrides.getOrDefault("threads", "4"), overrides.containsKey("threads"), null));
        }

        @Override
        public void apply(Map<String, String> overrides) {
            if (rejectAll) {
                throw new IllegalArgumentException("rejected");
            }
            applied = new LinkedHashMap<>(overrides);
        }
    }
}
