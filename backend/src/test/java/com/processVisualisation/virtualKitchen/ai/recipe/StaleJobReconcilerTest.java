package com.processVisualisation.virtualKitchen.ai.recipe;

import com.mongodb.client.result.UpdateResult;
import com.processVisualisation.virtualKitchen.ai.platform.credit.CreditReservation;
import com.processVisualisation.virtualKitchen.ai.platform.credit.CreditService;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestJob;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestStatus;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.model.RecipeProcessGenerationJob;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.model.VisualizationJob;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaleJobReconcilerTest {

    private MongoTemplate mongoTemplate;
    private CreditService creditService;
    private StaleJobReconciler reconciler;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        creditService = mock(CreditService.class);
        when(mongoTemplate.updateMulti(any(Query.class), any(Update.class), any(Class.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        reconciler = new StaleJobReconciler(mongoTemplate, creditService, 900_000, 3_600_000, 3_600_000);
    }

    @Test
    void reconcile_failsStaleJobsAndClearsTheirActiveKey() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(AiRequestJob.class)))
                .thenReturn(null);

        reconciler.reconcile();

        ArgumentCaptor<Update> generation = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateMulti(any(Query.class), generation.capture(), eq(RecipeProcessGenerationJob.class));
        assertFailedAndUnlocked(generation.getValue());

        ArgumentCaptor<Update> visualization = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateMulti(any(Query.class), visualization.capture(), eq(VisualizationJob.class));
        assertFailedAndUnlocked(visualization.getValue());

        ArgumentCaptor<Update> storyboard = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateMulti(any(Query.class), storyboard.capture(),
                eq(com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.RecipeStoryboard.class));
        assertFailedAndUnlocked(storyboard.getValue());
    }

    @Test
    void reconcile_staleProcessingRequest_releasesItsReservedCreditsOnce() {
        AiRequestJob processing = new AiRequestJob();
        processing.setId("req-1");
        processing.setUserId(7L);
        processing.setStatus(AiRequestStatus.PROCESSING);
        processing.setCreditCost(5);
        processing.setCreditTransactionId("tx-1");
        processing.setCapability(AiCapability.TEXT_TO_IMAGE);
        processing.setResolvedModelKey("img-model");

        AiRequestJob queued = new AiRequestJob();
        queued.setId("req-2");
        queued.setUserId(7L);
        queued.setStatus(AiRequestStatus.QUEUED);

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(AiRequestJob.class)))
                .thenReturn(processing, queued, null);

        reconciler.reconcile();

        ArgumentCaptor<CreditReservation> reservation = ArgumentCaptor.forClass(CreditReservation.class);
        verify(creditService, times(1)).release(eq(7L), reservation.capture(), eq("req-1"), any());
        assertEquals(5, reservation.getValue().cost());
        assertEquals("tx-1", reservation.getValue().transactionId());
        verify(creditService, never()).release(any(), any(), eq("req-2"), any());
    }

    @Test
    void queuedJobsWaitLongerThanRunningOnesBeforeBeingFailed() {
        java.time.Instant now = java.time.Instant.parse("2026-01-01T12:00:00Z");

        reconciler.failStaleGenerationJobs(now);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).updateMulti(query.capture(), any(Update.class), eq(RecipeProcessGenerationJob.class));
        @SuppressWarnings("unchecked")
        java.util.List<Document> branches = (java.util.List<Document>) query.getValue().getQueryObject().get("$or");
        java.util.Map<String, Object> cutoffByStatus = new java.util.HashMap<>();
        for (Document branch : branches) {
            cutoffByStatus.put(String.valueOf(branch.get("status")), ((Document) branch.get("updatedAt")).get("$lt"));
        }
        assertEquals(now.minusSeconds(15 * 60), cutoffByStatus.get("IN_PROGRESS"));
        assertEquals(now.minusSeconds(60 * 60), cutoffByStatus.get("QUEUED"));
    }

    private void assertFailedAndUnlocked(Update update) {
        Document set = (Document) update.getUpdateObject().get("$set");
        Document unset = (Document) update.getUpdateObject().get("$unset");
        assertEquals("FAILED", String.valueOf(set.get("status")));
        assertTrue(unset.containsKey("activeKey"));
    }
}
