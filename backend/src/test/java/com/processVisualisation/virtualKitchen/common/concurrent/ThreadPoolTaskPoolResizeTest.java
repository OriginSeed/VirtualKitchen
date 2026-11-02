package com.processVisualisation.virtualKitchen.common.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Admin thread settings resize a running pool in place, in both directions. */
class ThreadPoolTaskPoolResizeTest {

    private final ThreadPoolTaskPool pool = new ThreadPoolTaskPool(1, "resize-test");

    @AfterEach
    void tearDown() {
        pool.shutdown();
    }

    @Test
    void growingLetsMoreTasksRunAtOnce() throws Exception {
        pool.resize(3);
        CountDownLatch allRunning = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);

        List<NamedTask<Void>> tasks = IntStream.range(0, 3).mapToObj(i -> new NamedTask<Void>("t" + i, () -> {
            allRunning.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        })).toList();
        tasks.forEach(pool::submit);

        assertTrue(allRunning.await(5, TimeUnit.SECONDS), "three tasks run concurrently after growing to 3");
        release.countDown();
        assertEquals(3, pool.nThreads());
    }

    @Test
    void shrinkingAndGrowingAgainBothWork() throws Exception {
        pool.resize(4);
        pool.resize(2);
        assertEquals(2, pool.nThreads());

        TaskResult<String> result = pool.submit(new NamedTask<>("after-shrink", () -> "ok")).get(5, TimeUnit.SECONDS);
        assertEquals("ok", result.value());

        pool.resize(6);
        assertEquals(6, pool.nThreads());
    }

    @Test
    void lessThanOneThreadIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> pool.resize(0));
    }
}
