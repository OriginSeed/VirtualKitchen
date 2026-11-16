package com.processVisualisation.virtualKitchen.ai.platform.analytics;

import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.OperationContext;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Links an AI request to its recipe, process, step and workflow using identifiers that already exist: the queue's
 * correlation id ({@code recipeId::stepId} for step visuals and narration) and its operation id (the generation or
 * visualization job id), plus the workflow document that references those jobs. Only reliable links are made;
 * anything that cannot be resolved stays null.
 */
@Component
public class CorrelationResolver {

    static final String GENERATION_JOBS = "process_generation_job";
    static final String VISUALIZATION_JOBS = "visualization_job";
    static final String WORKFLOWS = "recipe_ai_workflow";
    static final String STORYBOARDS = "recipe_storyboard";

    private final MongoTemplate mongoTemplate;

    public CorrelationResolver(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public record Correlation(String recipeId, String processId, String stepId, String workflowId, Long jobUserId) {
    }

    public Correlation resolve(OperationContext context, String jobId, String correlationId, String knownWorkflowId) {
        String recipeId = null;
        String processId = null;
        String stepId = null;
        Long jobUserId = null;

        String[] recipeStep = recipeAndStep(context, correlationId);
        if (recipeStep != null) {
            recipeId = recipeStep[0];
            stepId = recipeStep[1];
        }
        if (jobId != null && (context == OperationContext.RECIPE_FLOW_GENERATION || context == OperationContext.RECIPE_FLOW_EDIT)) {
            Document job = mongoTemplate.findById(jobId, Document.class, GENERATION_JOBS);
            if (job != null) {
                recipeId = string(job.get("recipeId"));
                jobUserId = job.get("userId") instanceof Number n ? n.longValue() : null;
            }
        } else if (jobId != null && (context == OperationContext.IMAGE_PROMPT_GENERATION || context == OperationContext.STEP_IMAGE_GENERATION)) {
            Document job = mongoTemplate.findById(jobId, Document.class, VISUALIZATION_JOBS);
            if (job != null) {
                processId = string(job.get("processId"));
                if (recipeId == null) recipeId = string(job.get("recipeId"));
                jobUserId = job.get("userId") instanceof Number n ? n.longValue() : null;
            }
        } else if (jobId != null && (context == OperationContext.STORYBOARD_PLANNING || context == OperationContext.STORYBOARD_IMAGE)) {
            // A storyboard's job id is its document id.
            Document storyboard = mongoTemplate.findById(jobId, Document.class, STORYBOARDS);
            if (storyboard != null) {
                recipeId = string(storyboard.get("recipeId"));
                jobUserId = storyboard.get("userId") instanceof Number n ? n.longValue() : null;
            }
        }

        String workflowId = knownWorkflowId;
        if (workflowId == null && jobId != null) {
            Query byJob = Query.query(new Criteria().orOperator(
                    where("generationJobId").is(jobId), where("visualizationJobs.jobId").is(jobId)));
            byJob.fields().include("_id");
            Document workflow = mongoTemplate.findOne(byJob, Document.class, WORKFLOWS);
            if (workflow != null) {
                workflowId = string(workflow.get("_id"));
            }
        }
        return new Correlation(recipeId, processId, stepId, workflowId, jobUserId);
    }

    /** {@code [recipeId, stepId]} from a step-scoped correlation id, else null. */
    static String[] recipeAndStep(OperationContext context, String correlationId) {
        if (correlationId == null) {
            return null;
        }
        if (context != OperationContext.IMAGE_PROMPT_GENERATION && context != OperationContext.STEP_IMAGE_GENERATION
                && context != OperationContext.STEP_NARRATION) {
            return null;
        }
        String key = correlationId;
        int token = key.indexOf('|');
        if (token >= 0) {
            key = key.substring(0, token);
        }
        int separator = key.indexOf("::");
        if (separator <= 0 || separator + 2 >= key.length()) {
            return null;
        }
        return new String[]{key.substring(0, separator), key.substring(separator + 2)};
    }

    /** Narration and step visuals key their assets by {@code recipeId::stepId} (the correlation id minus any token). */
    static String assetKey(String correlationId) {
        if (correlationId == null) {
            return null;
        }
        int token = correlationId.indexOf('|');
        return token >= 0 ? correlationId.substring(0, token) : correlationId;
    }

    static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
