package com.processVisualisation.virtualKitchen.common.concurrent;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mints and caches named {@link TaskPool}s. Any feature that needs bounded
 * concurrent execution can call {@link #getOrCreate(String, int)} without
 * this package needing to change — the reuse point the task-pool framework
 * was designed around.
 */
@Component
public class TaskPoolFactory {

    private final Map<String, TaskPool> pools = new ConcurrentHashMap<>();

    /**
     * Returns the named pool if one already exists, otherwise creates and
     * caches a new {@link ThreadPoolTaskPool} sized with {@code nThreads}.
     * Lookup and creation are performed atomically via
     * {@link ConcurrentHashMap#computeIfAbsent}, so concurrent callers
     * requesting the same {@code name} are guaranteed to share one pool
     * instance. Note that {@code nThreads} only takes effect the first time
     * a given {@code name} is requested; later calls with the same name
     * return the already-created pool regardless of the value passed.
     *
     * @param name unique name identifying the pool; also used as the prefix
     *             for its worker thread names
     * @param nThreads number of worker threads to size a newly created pool
     *                 with; ignored if a pool with this name already exists
     * @return the existing or newly created {@link TaskPool} registered under {@code name}
     */
    public TaskPool getOrCreate(String name, int nThreads) {
        return pools.computeIfAbsent(name, poolName -> new ThreadPoolTaskPool(nThreads, poolName));
    }

    /** The named pool, if it has been created. */
    public Optional<TaskPool> find(String name) {
        return Optional.ofNullable(pools.get(name));
    }

    /**
     * Shuts down every pool this factory has created. Invoked automatically
     * by the Spring container on application shutdown via {@link PreDestroy}.
     */
    @PreDestroy
    public void shutdownAll() {
        pools.values().forEach(TaskPool::shutdown);
    }
}
