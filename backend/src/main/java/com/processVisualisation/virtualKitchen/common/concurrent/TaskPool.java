package com.processVisualisation.virtualKitchen.common.concurrent;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A bounded worker pool that runs {@link Task}s concurrently, limited to a
 * fixed number of threads ("nThreads"), and lets a caller either fire a single
 * task or run a batch and collect the aggregated result once every task in
 * the batch has finished (map-then-reduce-input).
 * <p>
 * Generic and feature-agnostic by design so other parts of the application
 * can reuse the same abstraction — see {@link TaskPoolFactory}.
 */
public interface TaskPool {

    /**
     * Submits a single task without blocking the caller.
     *
     * @param <R> the type of result produced by the task
     * @param task the task to run, together with its correlation id
     * @return a future that completes with the task's {@link TaskResult} —
     *         successful or failed — once execution finishes
     */
    <R> CompletableFuture<TaskResult<R>> submit(NamedTask<R> task);

    /**
     * Runs every task in {@code tasks} through this pool's bounded worker
     * threads, invoking {@code onTaskComplete} as each one finishes (useful
     * for incremental progress reporting), and blocks the calling thread
     * until all tasks have completed. The caller performs the actual reduce
     * step on the returned {@link BatchResult}.
     * <p>
     * A single failing task never aborts the batch or affects other tasks —
     * its outcome is captured as {@link TaskResult#failure}.
     *
     * @param <R> the type of result produced by each task
     * @param tasks the tasks to run
     * @param onTaskComplete callback invoked with each task's {@link TaskResult}
     *                       as it completes; may be called from a worker thread
     *                       and in an order that differs from {@code tasks},
     *                       since tasks can finish concurrently; may be
     *                       {@code null} to skip per-task notifications
     * @return the aggregated results of all tasks, in the same order as {@code tasks}
     */
    <R> BatchResult<R> submitAll(List<NamedTask<R>> tasks, Consumer<TaskResult<R>> onTaskComplete);

    /**
     * Returns the fixed number of worker threads backing this pool.
     *
     * @return the configured thread count
     */
    int nThreads();

    /**
     * Changes the number of worker threads while the pool runs (an admin runtime setting). Running tasks are never
     * interrupted: when shrinking, surplus threads exit once they finish their current task.
     *
     * @throws IllegalArgumentException if {@code nThreads} is less than 1
     * @throws UnsupportedOperationException if this pool can't be resized
     */
    default void resize(int nThreads) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " can't be resized");
    }

    /**
     * Shuts this pool down, releasing its worker threads. Tasks already
     * submitted are allowed to run to completion; no new tasks should be
     * submitted to this pool afterward.
     */
    void shutdown();
}
