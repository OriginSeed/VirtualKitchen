package com.processVisualisation.virtualKitchen.admin.config;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Setting keys contain dots ({@code credits.monthlyAllocation}), which Mongo refuses as map keys. The stored config
 * documents must therefore write and read back through Spring Data's real mapping with such keys intact.
 */
class RuntimeConfigMappingTest {

    private MappingMongoConverter converter;

    @BeforeEach
    void setUp() {
        MongoCustomConversions conversions = new MongoCustomConversions(List.of());
        MongoMappingContext context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
    }

    @Test
    void overridesWithDottedKeysRoundTrip() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("credits.monthlyAllocation", "200");
        values.put("model.gemini-flash.creditCost", "3");
        RuntimeConfigOverrides doc = new RuntimeConfigOverrides();
        doc.setSection("models");
        doc.putValues(values);

        Document stored = new Document();
        converter.write(doc, stored);
        RuntimeConfigOverrides read = converter.read(RuntimeConfigOverrides.class, stored);

        assertEquals(values, read.valueMap());
    }

    @Test
    void auditWithDottedKeysRoundTrips() {
        AdminConfigAudit audit = new AdminConfigAudit();
        audit.setSection("models");
        audit.setBefore(ConfigEntry.listOf(Map.of("credits.monthlyAllocation", "")));
        audit.setAfter(ConfigEntry.listOf(Map.of("credits.monthlyAllocation", "200")));

        Document stored = new Document();
        converter.write(audit, stored);
        AdminConfigAudit read = converter.read(AdminConfigAudit.class, stored);

        assertEquals(Map.of("credits.monthlyAllocation", "200"), ConfigEntry.mapOf(read.getAfter()));
    }
}
