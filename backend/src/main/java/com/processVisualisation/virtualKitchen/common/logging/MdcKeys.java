package com.processVisualisation.virtualKitchen.common.logging;

/**
 * MDC keys shared by the logging pattern in {@code logback-spring.xml} and the code that populates them.
 * <p>
 * Every key is copied to pool threads by {@code ThreadPoolTaskPool}, so a background job inherits the context of
 * the request that started it.
 */
public final class MdcKeys {

    /** Set per HTTP request by {@link RequestIdFilter}; propagated to pool threads by {@code ThreadPoolTaskPool}. */
    public static final String REQUEST_ID = "requestId";

    /** Set by {@code JwtAuthenticationFilter} once a bearer token has been validated. */
    public static final String USER_ID = "userId";

    /** Set by async job pipelines and scheduled jobs, linking their logs across threads and requests. */
    public static final String JOB_ID = "jobId";

    /** The job that started the current one, set by {@link OperationLog} when a job starts inside another. */
    public static final String PARENT_JOB_ID = "parentJobId";

    /** Feature area of the current action ({@code recipe}, {@code order}, {@code narration}, {@code system}...). */
    public static final String AREA = "area";

    /** The user action being served, e.g. {@code recipe.update}; set by {@code ActionContextInterceptor}. */
    public static final String ACTION = "action";

    /** Optional client-side name of the click that caused the request ({@code X-UI-Action}). */
    public static final String UI_ACTION = "uiAction";

    /** The AI workflow a line belongs to, set by {@code RecipeAiWorkflowService} around the jobs it starts. */
    public static final String WORKFLOW_ID = "workflowId";

    /** Ids copied from the request path by {@code ActionContextInterceptor}. */
    public static final String RECIPE_ID = "recipeId";
    public static final String PROCESS_ID = "processId";
    public static final String ORDER_ID = "orderId";
    public static final String STEP_ID = "stepId";

    private MdcKeys() {
    }
}
