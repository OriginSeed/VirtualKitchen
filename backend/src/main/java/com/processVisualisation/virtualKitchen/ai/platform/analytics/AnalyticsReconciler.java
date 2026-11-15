package com.processVisualisation.virtualKitchen.ai.platform.analytics;

import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AiRequestAnalytics;
import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AnalyticsStatus;
import com.processVisualisation.virtualKitchen.common.logging.LogScope;
import com.processVisualisation.virtualKitchen.common.logging.MdcKeys;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Closes live analytics records stuck in QUEUED/RUNNING (a completion write that was lost, or a restart mid-request),
 * so they are visible as such rather than looking in flight forever. Runs on the same cadence as
 * {@code StaleJobReconciler}, and never at start-up.
 */
@Component
public class AnalyticsReconciler {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsReconciler.class);

    private final MongoTemplate mongoTemplate;
    private final AiAnalyticsRecorder recorder;
    private final Duration staleAfter;

    public AnalyticsReconciler(MongoTemplate mongoTemplate, AiAnalyticsRecorder recorder,
                               @Value("${app.jobs.ai-request-stale-after-ms:3600000}") long staleAfterMs) {
        this.mongoTemplate = mongoTemplate;
        this.recorder = recorder;
        this.staleAfter = Duration.ofMillis(staleAfterMs);
    }

    @Scheduled(
            fixedDelayString = "${app.jobs.reconciler.fixed-delay-ms:300000}",
            initialDelayString = "${app.jobs.reconciler.fixed-delay-ms:300000}")
    public void reconcile() {
        // Scheduler threads have no request context; name this sweep so its lines group together.
        try (LogScope ignored = LogScope.with(MdcKeys.AREA, "system", MdcKeys.ACTION, "scheduled.analytics_reconcile",
                MdcKeys.JOB_ID, "analytics-reconcile-" + Long.toHexString(System.currentTimeMillis()))) {
            closeStaleRecords();
        }
    }

    private void closeStaleRecords() {
        Instant cutoff = Instant.now().minus(staleAfter);
        Query stale = Query.query(where("status").in(AnalyticsStatus.QUEUED.name(), AnalyticsStatus.RUNNING.name())
                .and("updatedAt").lt(cutoff)).limit(500);
        stale.fields().include("_id", "aiRequestJobId");
        List<AiRequestAnalytics> records = mongoTemplate.find(stale, AiRequestAnalytics.class);
        for (AiRequestAnalytics record : records) {
            Document job = record.getAiRequestJobId() == null ? null
                    : mongoTemplate.findById(record.getAiRequestJobId(), Document.class, "ai_request_jobs");
            recorder.reconcileStale(record.getId(), job == null ? null : job.getString("status"),
                    job == null ? null : job.getString("errorMessage"));
        }
        if (!records.isEmpty()) {
            log.warn("event=ai_analytics_stale_records_closed count={}", records.size());
        }
    }
}
