package com.processVisualisation.virtualKitchen.ai.platform.credit;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The monthly reset keeps in-flight reservations instead of zeroing them. */
class CreditCycleResetJobTest {

    @Test
    void resetSubtractsReservedCreditsFromTheNewAllocationAndKeepsThemReserved() {
        AggregationUpdate update = CreditCycleResetJob.resetUpdate(50, LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 1),
                LocalDateTime.of(2026, 12, 1, 2, 0));

        List<Document> pipeline = update.toPipeline(org.springframework.data.mongodb.core.aggregation.Aggregation.DEFAULT_CONTEXT);
        Document set = new Document();
        pipeline.forEach(stage -> set.putAll((Document) stage.get("$set")));

        Document reserved = new Document("$ifNull", List.of("$reservedBalance", 0));
        assertThat(set.get("reservedBalance")).isEqualTo(reserved);
        assertThat(set.get("availableBalance")).isEqualTo(new Document("$max", List.of(0,
                new Document("$subtract", List.of(50, reserved)))));
        assertThat(set.get("monthlyAllocation")).isEqualTo(50);
    }
}
