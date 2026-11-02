package com.processVisualisation.virtualKitchen.ai.recipe.workflow.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One "AI Recipe Creation" run: which tasks the user selected, the approval gate, and links to the
 * existing jobs that do the actual work. It owns no AI work of its own — process generation,
 * visualization and narration keep their own job records, and every task's progress is derived
 * from those on read (see {@code RecipeAiWorkflowStatusResolver}).
 * <p>
 * {@link #status} stores only what the jobs can't tell us: whether the user approved (or
 * discarded), and the terminal outcome once reached.
 */
@Data
@Document(collection = "recipe_ai_workflow")
public class RecipeAiWorkflow {

    @Id
    private String id;

    @Indexed
    private Long recipeId;

    private Long userId;

    private RecipeAiWorkflowStatus status;

    /** Distinct, in {@link RecipeAiTaskType} order. */
    private List<RecipeAiTaskType> selectedTasks = new ArrayList<>();

    /** The recipe text the process was generated from (kept so a failed generation can be retried). */
    private String recipeText;

    /** The process generation job (PROCESS task); null when PROCESS wasn't selected. */
    private String generationJobId;

    /**
     * Models chosen for this run only (capability name to model key), applied to every task it starts, retries
     * included (see {@code AiRunModels}). Empty: the user's saved choices, then the defaults.
     */
    private Map<String, String> modelKeys = new LinkedHashMap<>();

    /** The latest visualization job per approved process (VISUALS task); a retry replaces the entry. */
    private List<VisualizationJobRef> visualizationJobs = new ArrayList<>();

    /** The exact recipe state downstream tasks were started from; null until approval. */
    private ApprovedSnapshot approvedSnapshot;

    /**
     * {@code wf:<recipeId>} while the workflow is in progress or waiting for approval, unset once it
     * reaches a terminal state. The unique sparse index allows one open workflow per recipe.
     */
    @Indexed(unique = true, sparse = true)
    private String activeKey;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant completedAt;

    /** Set when the user closes the final summary, so it is no longer offered on reopen. */
    private Instant dismissedAt;

    public boolean isSelected(RecipeAiTaskType task) {
        return selectedTasks != null && selectedTasks.contains(task);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VisualizationJobRef {
        private Long processId;
        private String jobId;
    }

    /**
     * The recipe as the user approved it: the saved process revision, and per process the STEP ids
     * (in step order) that visuals and narration were started for. Progress is counted against these.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovedSnapshot {
        private Long revision;
        private Instant approvedAt;
        private List<ApprovedProcess> processes = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovedProcess {
        private Long processId;
        private String name;
        private List<String> stepIds = new ArrayList<>();
    }
}
