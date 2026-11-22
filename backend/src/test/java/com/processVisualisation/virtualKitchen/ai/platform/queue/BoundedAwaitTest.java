package com.processVisualisation.virtualKitchen.ai.platform.queue;

import com.processVisualisation.virtualKitchen.common.concurrent.TaskResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AiRequestQueueService#awaitBounded}: the AI timeout counts from when a worker starts the call, a call the
 * caller gave up on before it started never runs, and a call still running after a timeout keeps its queue slot.
 */
class BoundedAwaitTest {

    @Test
    void timeSpentWaitingForAWorkerDoesNotCountAgainstTheCall() throws Exception {
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch release = new CountDownLatch(1);
            single.submit(() -> release.await(5, TimeUnit.SECONDS)); // occupies the only worker for a while

            AiRequestQueueService.StartGate gate = new AiRequestQueueService.StartGate();
            CompletableFuture<TaskResult<String>> future = CompletableFuture.supplyAsync(() -> {
                if (!gate.start()) return TaskResult.failure("t", new IllegalStateException("abandoned"));
                sleep(150);
                return TaskResult.success("t", "done");
            }, single);

            // Queued 300 ms, runs 150 ms: 450 ms in total, but under the 400 ms budget counted from the start.
            Thread.sleep(300);
            release.countDown();
            assertThat(AiRequestQueueService.awaitBounded(future, gate, 400, null)).isEqualTo("done");
        } finally {
            single.shutdownNow();
        }
    }

    @Test
    void aCallAbandonedBeforeItStartsNeverRuns() throws Exception {
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch release = new CountDownLatch(1);
            single.submit(() -> release.await(5, TimeUnit.SECONDS));

            AiRequestQueueService.StartGate gate = new AiRequestQueueService.StartGate();
            AtomicBoolean providerCalled = new AtomicBoolean();
            CompletableFuture<TaskResult<String>> future = CompletableFuture.supplyAsync(() -> {
                if (!gate.start()) return TaskResult.failure("t", new IllegalStateException("abandoned"));
                providerCalled.set(true);
                return TaskResult.success("t", "done");
            }, single);

            assertThatThrownBy(() -> AiRequestQueueService.awaitBounded(future, gate, 100, null))
                    .isInstanceOf(TimeoutException.class).hasMessageContaining("free worker");

            release.countDown();
            future.get(2, TimeUnit.SECONDS);
            assertThat(providerCalled).isFalse();
        } finally {
            single.shutdownNow();
        }
    }

    @Test
    void aCallStillRunningAfterTheTimeoutKeepsItsQueueSlotUntilItEnds() throws Exception {
        AtomicInteger inFlight = new AtomicInteger(1);
        AiRequestQueueService.AdmissionTicket ticket = new AiRequestQueueService.AdmissionTicket(inFlight);
        AiRequestQueueService.StartGate gate = new AiRequestQueueService.StartGate();
        CountDownLatch finish = new CountDownLatch(1);
        CompletableFuture<TaskResult<String>> future = CompletableFuture.supplyAsync(() -> {
            gate.start();
            try {
                finish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return TaskResult.success("t", "late");
        });

        assertThatThrownBy(() -> AiRequestQueueService.awaitBounded(future, gate, 100, ticket))
                .isInstanceOf(TimeoutException.class);
        ticket.releaseUnlessDeferred();
        assertThat(inFlight.get()).as("still running, still counted").isEqualTo(1);

        finish.countDown();
        future.get(2, TimeUnit.SECONDS);
        Thread.sleep(50);
        assertThat(inFlight.get()).isZero();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
