package com.processVisualisation.virtualKitchen.ai.recipe;

import com.processVisualisation.virtualKitchen.ai.recipe.generation.model.RecipeProcessGenerationJob;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.RecipeStoryboard;
import com.processVisualisation.virtualKitchen.ai.recipe.storyboard.model.StoryboardStepFix;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.model.VisualizationJob;
import com.processVisualisation.virtualKitchen.common.web.ApiException;
import com.processVisualisation.virtualKitchen.common.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Admission control for background AI jobs (recipe generation, visualization, storyboards, step-image fixes), checked
 * before a new job is created. Jobs wait in in-memory pools, so without a limit one user, or a busy moment, can queue
 * work that only runs many minutes later (and may be failed as stale meanwhile). Two soft limits:
 * <ul>
 *   <li>{@code app.jobs.max-active-per-user} — jobs one user may have QUEUED or IN_PROGRESS at once, across all
 *       kinds, so one person can't fill the queue for everyone;</li>
 *   <li>{@code app.jobs.max-queued} — jobs waiting (QUEUED) system-wide; beyond it new work is refused with a
 *       friendly "busy" answer instead of waiting a very long time.</li>
 * </ul>
 * The checks are counts taken before the insert, so two simultaneous starts can overshoot a limit by one; that is
 * fine for a fairness limit. A refusal is a 429 with {@link ErrorCode#AI_BUSY}.
 */
@Component
public class AiJobAdmission {

    private static final Logger log = LoggerFactory.getLogger(AiJobAdmission.class);

    private static final List<Class<?>> JOB_TYPES = List.of(
            RecipeProcessGenerationJob.class, VisualizationJob.class, RecipeStoryboard.class, StoryboardStepFix.class);

    private final MongoTemplate mongoTemplate;
    private final int maxActivePerUser;
    private final int maxQueued;

    public AiJobAdmission(MongoTemplate mongoTemplate,
                          @Value("${app.jobs.max-active-per-user:10}") int maxActivePerUser,
                          @Value("${app.jobs.max-queued:60}") int maxQueued) {
        this.mongoTemplate = mongoTemplate;
        this.maxActivePerUser = maxActivePerUser;
        this.maxQueued = maxQueued;
    }

    /**
     * Refuses a new job when {@code userId} already has too many running, or too many jobs are waiting overall.
     *
     * @throws AiJobLimitException when a limit is reached
     */
    public void checkCanStart(Long userId) {
        long active = 0;
        long queued = 0;
        for (Class<?> type : JOB_TYPES) {
            if (userId != null) {
                active += mongoTemplate.count(Query.query(where("userId").is(userId)
                        .and("status").in("QUEUED", "IN_PROGRESS")), type);
            }
            queued += mongoTemplate.count(Query.query(where("status").is("QUEUED")), type);
        }
        if (active >= maxActivePerUser) {
            log.warn("event=ai_job_rejected reason=user_limit active={} limit={}", active, maxActivePerUser);
            throw new AiJobLimitException(
                    "You already have several AI tasks running. Please wait for one of them to finish, then try again.");
        }
        if (queued >= maxQueued) {
            log.warn("event=ai_job_rejected reason=queue_full queued={} limit={}", queued, maxQueued);
            throw new AiJobLimitException(
                    "The AI kitchen is busy right now. Please try again in a few minutes.");
        }
    }

    /** A new AI job was refused by {@link AiJobAdmission}: 429, code {@link ErrorCode#AI_BUSY}. */
    public static class AiJobLimitException extends ApiException {
        public AiJobLimitException(String message) {
            super(message, HttpStatus.TOO_MANY_REQUESTS, "AI System Busy", Outcome.REJECTED);
        }

        @Override
        public ErrorCode getCode() {
            return ErrorCode.AI_BUSY;
        }
    }
}
