package com.processVisualisation.virtualKitchen.common.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link TaskPool} backed by a fixed-size {@link ExecutorService} (resizable at runtime). Every task
 * body is wrapped so any exception is captured as a {@link TaskResult#failure}
 * rather than being allowed to escape the worker thread.
 * <p>
 * The submitting thread's logging MDC (request ID, job ID, user ID) is copied
 * onto the worker for the duration of each task and the worker's previous MDC
 * restored afterwards, so pooled threads never carry one request's IDs into
 * another's logs.
 */
public class ThreadPoolTaskPool implements TaskPool {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolTaskPool.class);

    private volatile int nThreads;
    private final ThreadPoolExecutor executor;

    /**
     * Creates a pool backed by a fixed-size {@link ExecutorService}, whose
     * threads are named after {@code poolName} and marked as daemon threads
     * so they never prevent JVM shutdown.
     *
     * @param nThreads number of worker threads to run tasks on; must be >= 1
     * @param poolName prefix used to name worker threads (e.g. {@code "poolName-pool-1"}),
     *                 useful for identifying threads in logs and thread dumps
     * @throws IllegalArgumentException if {@code nThreads} is less than 1
     */
    public ThreadPoolTaskPool(int nThreads, String poolName) {
        if (nThreads < 1) {
            throw new IllegalArgumentException("nThreads must be >= 1, got " + nThreads);
        }
        this.nThreads = nThreads;
        // Same as Executors.newFixedThreadPool (core = max, unbounded queue), typed so it can be resized.
        this.executor = new ThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), namedThreadFactory(poolName));
    }

    private static ThreadFactory namedThreadFactory(String poolName) {
        AtomicLong counter = new AtomicLong(0);
        return runnable -> {
            Thread thread = new Thread(runnable, poolName + "-pool-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * {@inheritDoc}
     * The task body runs asynchronously on this pool's executor; any
     * exception it throws is caught by {@link #runSafely} and converted into
     * a {@link TaskResult#failure} rather than failing the returned future.
     */
    @Override
    public <R> CompletableFuture<TaskResult<R>> submit(NamedTask<R> task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return CompletableFuture.supplyAsync(() -> withMdc(context, () -> runSafely(task)), executor);
    }

    /**
     * {@inheritDoc}
     * Every task is submitted to the executor up front; {@code onTaskComplete}
     * runs on whichever worker thread finishes a given task, so it may be
     * invoked concurrently and out of the order {@code tasks} were passed in.
     * This method blocks the calling thread (via {@link CompletableFuture#join()})
     * until every submitted task has completed before assembling and
     * returning the aggregated {@link BatchResult}.
     */
    @Override
    public <R> BatchResult<R> submitAll(List<NamedTask<R>> tasks, Consumer<TaskResult<R>> onTaskComplete) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        List<CompletableFuture<TaskResult<R>>> futures = new ArrayList<>(tasks.size());
        for (NamedTask<R> task : tasks) {
            // The callback runs inside the same MDC scope as the task, so its logs carry the caller's IDs too.
            CompletableFuture<TaskResult<R>> future = CompletableFuture.supplyAsync(() -> withMdc(context, () -> {
                TaskResult<R> result = runSafely(task);
                if (onTaskComplete != null) {
                    onTaskComplete.accept(result);
                }
                return result;
            }), executor);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<TaskResult<R>> results = futures.stream().map(CompletableFuture::join).toList();
        return new BatchResult<>(results);
    }

    /**
     * Executes {@code task} and converts any thrown {@link Throwable} into a
     * failed {@link TaskResult} instead of letting it escape onto the worker
     * thread; the failure is also logged at DEBUG level (the caller owns reporting it).
     *
     * @param <R> the type of result produced by the task
     * @param task the task to run
     * @return a successful result if the task completed normally, otherwise
     *         a failed result wrapping the thrown exception
     */
    private <R> TaskResult<R> runSafely(NamedTask<R> task) {
        try {
            return TaskResult.success(task.taskId(), task.task().execute());
        } catch (Throwable t) {
            // The caller receives the failure and owns logging it; this keeps the stack out of the main log twice.
            log.debug("event=task_failed taskId={} errorType={}", task.taskId(), t.getClass().getSimpleName(), t);
            return TaskResult.failure(task.taskId(), t);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int nThreads() {
        return nThreads;
    }

    /**
     * {@inheritDoc}
     * Core and maximum size move together (a fixed pool). The order matters: the core size may never exceed the
     * maximum, so growing raises the maximum first and shrinking lowers the core first.
     */
    @Override
    public synchronized void resize(int nThreads) {
        if (nThreads < 1) {
            throw new IllegalArgumentException("nThreads must be >= 1, got " + nThreads);
        }
        if (nThreads > executor.getMaximumPoolSize()) {
            executor.setMaximumPoolSize(nThreads);
            executor.setCorePoolSize(nThreads);
        } else {
            executor.setCorePoolSize(nThreads);
            executor.setMaximumPoolSize(nThreads);
        }
        this.nThreads = nThreads;
    }

    /**
     * {@inheritDoc}
     * Delegates to {@link ExecutorService#shutdown()}: previously submitted
     * tasks continue running to completion, but the executor stops accepting
     * new tasks.
     */
    @Override
    public void shutdown() {
        executor.shutdown();
    }

    /** Runs {@code body} with {@code context} as the MDC, then restores whatever the worker thread had before. */
    private static <T> T withMdc(Map<String, String> context, Supplier<T> body) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (context != null) {
            MDC.setContextMap(context);
        } else {
            MDC.clear();
        }
        try {
            return body.get();
        } finally {
            if (previous != null) {
                MDC.setContextMap(previous);
            } else {
                MDC.clear();
            }
        }
    }
}
