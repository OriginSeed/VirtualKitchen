package com.processVisualisation.virtualKitchen.ai.recipe;

import com.processVisualisation.virtualKitchen.common.logging.LogScope;

import com.processVisualisation.virtualKitchen.ai.platform.credit.CreditReservation;
import com.processVisualisation.virtualKitchen.ai.platform.credit.CreditService;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestJob;
import com.processVisualisation.virtualKitchen.ai.platform.queue.AiRequestStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.model.RecipeProcessGenerationJob;
import com.processVisualisation.virtualKitchen.ai.recipe.generation.model.RecipeProcessGenerationJobStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.RecipeStoryboard;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.StoryboardStatus;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.model.VisualizationJob;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.model.VisualizationJobStatus;
import com.processVisualisation.virtualKitchen.common.logging.MdcKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Fails background jobs that no live worker is driving any more, so they stop blocking the UI.
 * <p>
 * Generation and visualization jobs run on in-memory thread pools; if the JVM stops mid-job, the
 * job document stays QUEUED/IN_PROGRESS forever, keeps its {@code activeKey} (which blocks a new
 * start for the same recipe/process), and a polling client spins on it indefinitely. Likewise an
 * {@link AiRequestJob} left PROCESSING keeps its reserved credits held until the monthly reset.
 * <p>
 * A job counts as stale once it hasn't been written for longer than the configured threshold,
 * which sits well above every AI call timeout: live jobs touch {@code updatedAt} on every stage
 * change or finished step. Runs once at startup and then periodically. Safe on multiple
 * instances: every transition is conditional on the job still being active and still stale,
 * and credits are released only by the instance whose {@code findAndModify} claimed the request.
 */
@Component
public class StaleJobReconciler {

    private static final Logger log = LoggerFactory.getLogger(StaleJobReconciler.class);

    static final String INTERRUPTED_MESSAGE = "This job was interrupted before it finished. Please try again.";

    private final MongoTemplate mongoTemplate;
    private final CreditService creditService;
    private final Duration jobStaleAfter;
    private final Duration aiRequestStaleAfter;

    public StaleJobReconciler(
            MongoTemplate mongoTemplate,
            CreditService creditService,
            @Value("${app.jobs.stale-after-ms:900000}") long jobStaleAfterMs,
            @Value("${app.jobs.ai-request-stale-after-ms:3600000}") long aiRequestStaleAfterMs) {
        this.mongoTemplate = mongoTemplate;
        this.creditService = creditService;
        this.jobStaleAfter = Duration.ofMillis(jobStaleAfterMs);
        this.aiRequestStaleAfter = Duration.ofMillis(aiRequestStaleAfterMs);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        reconcile();
    }

    @Scheduled(
            fixedDelayString = "${app.jobs.reconciler.fixed-delay-ms:300000}",
            initialDelayString = "${app.jobs.reconciler.fixed-delay-ms:300000}")
    public void reconcile() {
        // Scheduler threads have no request ID; a per-sweep job ID groups this sweep's log lines instead.
        try (LogScope ignored = LogScope.with(MdcKeys.AREA, "system", MdcKeys.ACTION, "scheduled.stale_job_sweep", MdcKeys.JOB_ID,
                "stale-job-sweep-" + Long.toHexString(System.currentTimeMillis()))) {
            Instant now = Instant.now();
            failStaleGenerationJobs(now);
            failStaleVisualizationJobs(now);
            failStaleStoryboards(now);
            failStaleStepFixes(now);
            failStaleAiRequests(now);
        } catch (Exception e) {
            // Never let one bad sweep kill the schedule; the next run retries.
            log.warn("event=stale_job_sweep_failed error={}", e.toString());
        }
    }

    long failStaleGenerationJobs(Instant now) {
        Query stale = Query.query(where("status").in(RecipeProcessGenerationJobStatus.QUEUED, RecipeProcessGenerationJobStatus.IN_PROGRESS)
                .and("updatedAt").lt(now.minus(jobStaleAfter)));
        long failed = mongoTemplate.updateMulti(stale, interruptedUpdate(RecipeProcessGenerationJobStatus.FAILED, now),
                RecipeProcessGenerationJob.class).getModifiedCount();
        if (failed > 0) {
            log.warn("event=stale_generation_jobs_failed count={}", failed);
        }
        return failed;
    }

    long failStaleVisualizationJobs(Instant now) {
        Query stale = Query.query(where("status").in(VisualizationJobStatus.QUEUED, VisualizationJobStatus.IN_PROGRESS)
                .and("updatedAt").lt(now.minus(jobStaleAfter)));
        long failed = mongoTemplate.updateMulti(stale, interruptedUpdate(VisualizationJobStatus.FAILED, now),
                VisualizationJob.class).getModifiedCount();
        if (failed > 0) {
            log.warn("event=stale_visualization_jobs_failed count={}", failed);
        }
        return failed;
    }

    long failStaleStoryboards(Instant now) {
        Query stale = Query.query(where("status").in(StoryboardStatus.QUEUED, StoryboardStatus.IN_PROGRESS)
                .and("updatedAt").lt(now.minus(jobStaleAfter)));
        long failed = mongoTemplate.updateMulti(stale, interruptedUpdate(StoryboardStatus.FAILED, now),
                RecipeStoryboard.class).getModifiedCount();
        if (failed > 0) {
            log.warn("event=stale_storyboards_failed count={}", failed);
        }
        return failed;
    }

    long failStaleStepFixes(Instant now) {
        Query stale = Query.query(where("status").in(StoryboardStatus.QUEUED, StoryboardStatus.IN_PROGRESS)
                .and("updatedAt").lt(now.minus(jobStaleAfter)));
        long failed = mongoTemplate.updateMulti(stale, interruptedUpdate(StoryboardStatus.FAILED, now),
                com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.StoryboardStepFix.class).getModifiedCount();
        if (failed > 0) {
            log.warn("event=stale_step_fixes_failed count={}", failed);
        }
        return failed;
    }

    /**
     * Claims stale requests one at a time so each one's credit reservation is released exactly
     * once, by whichever instance's {@code findAndModify} flipped it out of its active status.
     */
    int failStaleAiRequests(Instant now) {
        Instant cutoff = now.minus(aiRequestStaleAfter);
        Query stale = Query.query(new Criteria().orOperator(
                where("status").is(AiRequestStatus.PROCESSING).and("startedAt").lt(cutoff),
                where("status").is(AiRequestStatus.QUEUED).and("queuedAt").lt(cutoff)));
        Update update = new Update()
                .set("status", AiRequestStatus.FAILED)
                .set("errorMessage", INTERRUPTED_MESSAGE)
                .set("completedAt", now);

        int failed = 0;
        AiRequestJob claimed;
        // Returns the pre-update document, so its status says whether a reservation was held.
        while ((claimed = mongoTemplate.findAndModify(stale, update, FindAndModifyOptions.options().returnNew(false), AiRequestJob.class)) != null) {
            failed++;
            if (claimed.getStatus() == AiRequestStatus.PROCESSING && claimed.getCreditCost() > 0) {
                creditService.release(claimed.getUserId(),
                        new CreditReservation(claimed.getCreditTransactionId(), claimed.getCreditCost(),
                                claimed.getCapability(), claimed.getResolvedModelKey()),
                        claimed.getId(), claimed.getUsage());
            }
            log.warn("event=stale_ai_request_failed aiRequestId={} previousStatus={} creditsReleased={}",
                    claimed.getId(), claimed.getStatus(),
                    claimed.getStatus() == AiRequestStatus.PROCESSING ? claimed.getCreditCost() : 0);
        }
        return failed;
    }

    private static Update interruptedUpdate(Enum<?> failedStatus, Instant now) {
        return new Update()
                .set("status", failedStatus)
                .set("errorMessage", INTERRUPTED_MESSAGE)
                .set("completedAt", now)
                .set("updatedAt", now)
                .unset("activeKey");
    }
}
