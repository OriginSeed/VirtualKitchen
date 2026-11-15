package com.processVisualisation.virtualKitchen.ai.platform.artifact;

import com.processVisualisation.virtualKitchen.common.logging.LogScope;

import com.processVisualisation.virtualKitchen.ai.platform.artifact.consumer.AiArtifactConsumer;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.consumer.AiArtifactConsumerRegistry;
import com.processVisualisation.virtualKitchen.common.logging.MdcKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Re-drives dependent work for AI payloads whose consumer never completed, and collects the
 * payloads of artifacts that have since been retired.
 * <p>
 * This is what makes the artifact store survive a process death: if the JVM stops between the
 * provider call and the upload that consumes its output, nothing in the request path is left to
 * finish the job, but the payload is already durable — so this sweeper picks it up and completes
 * the upload over the result that was already paid for.
 *
 * <h2>The invariant that makes this safe</h2>
 * This job never touches {@code AiRequestQueueService}, {@code ModelSelectionService},
 * {@code CreditService}, or any {@code ImageGenerationClient}. It only re-runs the
 * <em>dependent</em> work over an already-paid payload, which makes a bug in it structurally
 * incapable of calling a provider or spending a credit.
 *
 * <h2>Not fighting the request path</h2>
 * Three independent mechanisms keep this job away from work a live request is doing: the
 * {@code orphanAgeMs} grace window, the lease taken by {@code AiArtifactService.findReusable},
 * and the {@code status = PENDING} guard in {@code markConsumed} that elects a single winner.
 * Worst case — a lease expiring mid-upload — is two uploads to two distinct paths, where the
 * losing {@code markConsumed} returns false and its caller discards the duplicate.
 * <p>
 * Safe to run on multiple instances: every claim is an atomic, lease-checked
 * {@code findAndModify}, so two sweepers cannot take the same artifact.
 */
@Component
public class AiArtifactRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(AiArtifactRecoveryJob.class);

    private final AiArtifactService artifactService;
    private final AiArtifactConsumerRegistry consumerRegistry;
    private final AiArtifactProperties properties;

    public AiArtifactRecoveryJob(
            AiArtifactService artifactService,
            AiArtifactConsumerRegistry consumerRegistry,
            AiArtifactProperties properties) {
        this.artifactService = artifactService;
        this.consumerRegistry = consumerRegistry;
        this.properties = properties;
    }

    /**
     * One sweep: recover orphaned payloads, then collect blobs left behind by retired artifacts.
     */
    @Scheduled(
            fixedDelayString = "${ai.artifact.sweeper.fixed-delay-ms:300000}",
            initialDelayString = "${ai.artifact.sweeper.initial-delay-ms:60000}")
    public void sweep() {
        if (!properties.isEnabled() || !properties.getSweeper().isEnabled()) {
            return;
        }
        // Scheduler threads have no request ID; a per-sweep job ID groups this sweep's log lines instead.
        try (LogScope ignored = LogScope.with(MdcKeys.AREA, "system", MdcKeys.ACTION, "scheduled.artifact_sweep", MdcKeys.JOB_ID,
                "artifact-sweep-" + Long.toHexString(System.currentTimeMillis()))) {
            recoverOrphans();
            collectRetiredPayloads();
        }
    }

    /**
     * Pass 1 — completes dependent work for pending artifacts that no live request is holding.
     */
    private void recoverOrphans() {
        List<AiArtifact> orphans = artifactService.findOrphans(
                Instant.now(), properties.getSweeper().getBatchSize());
        if (orphans.isEmpty()) {
            return;
        }

        int recovered = 0;
        for (AiArtifact orphan : orphans) {
            if (recover(orphan)) {
                recovered++;
            }
        }
        log.info("event=ai_artifact_recovery_completed recovered={} orphans={}", recovered, orphans.size());
    }

    private boolean recover(AiArtifact orphan) {
        Optional<AiArtifact> claimed = artifactService.acquireLease(orphan.getId());
        if (claimed.isEmpty()) {
            // Another sweeper or a live request took it first.
            return false;
        }
        AiArtifact artifact = claimed.get();

        Optional<AiArtifactConsumer> consumer = consumerRegistry.find(artifact.getConsumerId());
        if (consumer.isEmpty()) {
            artifactService.markRecoveryFailure(artifact.getId(),
                    "No consumer registered for id '" + artifact.getConsumerId() + "'");
            return false;
        }

        Optional<AiArtifactService.LoadedArtifact> loaded = artifactService.load(artifact);
        if (loaded.isEmpty()) {
            artifactService.markRecoveryFailure(artifact.getId(), "Payload could not be decoded");
            return false;
        }

        try {
            consumer.get().consume(artifact, loaded.get().value());
            return artifactService.markConsumed(artifact.getId());
        } catch (Exception e) {
            log.warn("event=ai_artifact_recovery_failed artifactId={} artifactKey={} errorType={} payloadRetained=true",
                    artifact.getId(), artifact.getArtifactKey(), e.getClass().getSimpleName(), e);
            artifactService.markRecoveryFailure(artifact.getId(), safeMessage(e));
            return false;
        }
    }

    /**
     * Pass 2 — deletes payloads still referenced by consumed or abandoned artifacts, which happens
     * when a process died between the status transition and the payload delete. Without this the
     * blob would become unreferenced once the metadata row's TTL removed it.
     */
    private void collectRetiredPayloads() {
        List<AiArtifact> collectable = artifactService.findCollectableBlobs(
                properties.getSweeper().getBatchSize());
        for (AiArtifact artifact : collectable) {
            artifactService.releasePayload(artifact.getId());
        }
        if (!collectable.isEmpty()) {
            log.info("event=ai_artifact_blobs_collected count={}", collectable.size());
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message != null ? e.getClass().getSimpleName() + ": " + message : e.getClass().getSimpleName();
    }
}
