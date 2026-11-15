package com.processVisualisation.virtualKitchen.ai.platform.artifact;

import com.processVisualisation.virtualKitchen.common.logging.UsageLedger;

import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.result.UpdateResult;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.codec.AiArtifactCodec;
import com.processVisualisation.virtualKitchen.ai.platform.artifact.codec.AiArtifactCodecRegistry;
import com.processVisualisation.virtualKitchen.ai.platform.registry.AiCapability;
import com.processVisualisation.virtualKitchen.ai.platform.routing.ModelSelectionOutcome;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Persists AI payloads the instant they are produced, serves them back instead of paying for a
 * regeneration, and retires them once the dependent work has succeeded.
 * <p>
 * Every mutation here is a single guarded {@code findAndModify}/{@code updateFirst} rather than a
 * read-modify-save, following the convention set by {@code CreditService} and
 * {@code RecipeProcessVisualizationJobService}: the request path and {@link AiArtifactRecoveryJob} write these
 * documents concurrently, and a lost update would either resurrect a consumed artifact or drop a
 * paid payload.
 *
 * @see AiArtifact
 */
@Service
public class AiArtifactService {

    private static final Logger log = LoggerFactory.getLogger(AiArtifactService.class);

    private final MongoTemplate mongoTemplate;
    private final GridFsTemplate gridFsTemplate;
    private final AiArtifactRepository artifactRepository;
    private final AiArtifactCodecRegistry codecRegistry;
    private final AiArtifactProperties properties;

    public AiArtifactService(
            MongoTemplate mongoTemplate,
            GridFsTemplate gridFsTemplate,
            AiArtifactRepository artifactRepository,
            AiArtifactCodecRegistry codecRegistry,
            AiArtifactProperties properties) {
        this.mongoTemplate = mongoTemplate;
        this.gridFsTemplate = gridFsTemplate;
        this.artifactRepository = artifactRepository;
        this.codecRegistry = codecRegistry;
        this.properties = properties;
    }

    /**
     * An artifact loaded by the recovery sweeper, which knows its payload type only at runtime.
     *
     * @param artifact the artifact row
     * @param value the decoded payload
     */
    public record LoadedArtifact(AiArtifact artifact, Object value) {
    }

    /**
     * Finds a payload that can be reused instead of calling the provider again, and takes a
     * lease on it.
     * <p>
     * The {@code status = PENDING} guard is in the <em>query</em> deliberately: a {@code CONSUMED}
     * row still occupies the dedup slot until its TTL expires, but its payload is gone, so it must
     * read as a miss. Taking the lease in the same atomic operation is what pushes the recovery
     * sweeper away from an artifact a live request is about to consume.
     *
     * @param userId the requesting user; artifacts are never shared across users
     * @param spec the artifact key and codec for this call site
     * @return the artifact and its decoded payload, or empty if nothing reusable exists
     */
    public <R> Optional<AiArtifactHit<R>> findReusable(Long userId, AiArtifactSpec<R> spec) {
        if (!properties.isEnabled() || spec == null) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        Query query = query(where("userId").is(userId)
                .and("artifactKey").is(spec.artifactKey())
                .and("status").is(AiArtifactStatus.PENDING));
        Update update = new Update()
                .inc("reuseCount", 1)
                .set("updatedAt", now)
                .set("leaseUntil", now.plusMillis(properties.getLeaseTtlMs()));

        AiArtifact artifact = mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(true), AiArtifact.class);
        if (artifact == null) {
            return Optional.empty();
        }

        try {
            AiArtifactPayload payload = readPayload(artifact);
            R value = spec.codec().decode(payload);
            UsageLedger.increment(UsageLedger.Counter.ARTIFACTS_REUSED);
            log.info("event=ai_artifact_reused artifactId={} artifactKey={} reuseCount={} providerCall=false",
                    artifact.getId(), artifact.getArtifactKey(), artifact.getReuseCount());
            return Optional.of(new AiArtifactHit<>(artifact, value));
        } catch (Exception e) {
            // A payload we cannot read is not worth failing the request over — the caller simply
            // regenerates, which is exactly what would have happened without the store.
            log.warn("event=ai_artifact_read_failed artifactId={} artifactKey={} fallback=regenerate errorType={}",
                    artifact.getId(), artifact.getArtifactKey(), e.getClass().getSimpleName(), e);
            return Optional.empty();
        }
    }

    /**
     * Persists a freshly generated payload before any dependent work runs.
     * <p>
     * This is an upsert into the {@code (userId, artifactKey)} slot rather than an insert,
     * because a {@code CONSUMED} row keeps occupying that slot until its TTL expires; inserting
     * would hit the unique index. Note the {@code unset("expiresAt")}, which lifts the TTL back
     * off a slot being reused for a new payload.
     *
     * @param userId the owning user
     * @param capability the capability that produced the payload
     * @param correlationType the work category, e.g. {@code "visualization-image"}
     * @param correlationId the domain scope, e.g. {@code recipeId::stepId}
     * @param spec the artifact key, codec, and consumer for this call site
     * @param value the provider's result
     * @param selection which model served the call, and any credit reservation
     * @param producingJobId the {@code AiRequestJob.id} that was billed
     * @return the staged artifact
     * @throws Exception if the payload cannot be encoded or stored
     */
    public <R> AiArtifact stage(
            Long userId,
            AiCapability capability,
            String correlationType,
            String correlationId,
            AiArtifactSpec<R> spec,
            R value,
            ModelSelectionOutcome selection,
            String producingJobId) throws Exception {

        if (!properties.isEnabled() || spec == null) {
            return null;
        }

        AiArtifactPayload payload = spec.codec().encode(value);
        // Captured before the write so a blob left by a previous incarnation of this slot can be
        // collected afterwards. A race here is benign: the worst case is a stale blob that the
        // sweeper's GC pass picks up.
        String previousGridFsId = artifactRepository.findByUserIdAndArtifactKey(userId, spec.artifactKey())
                .map(AiArtifact::getGridFsId)
                .orElse(null);

        boolean storeAsBinary = payload.kind() == AiArtifactPayloadKind.BINARY
                || payload.size() > properties.getMaxInlineBytes();
        String gridFsId = storeAsBinary ? storeBlob(userId, spec.artifactKey(), payload) : null;
        String inlinePayload = storeAsBinary ? null : payload.asText();

        Instant now = Instant.now();
        Update update = new Update()
                .setOnInsert("_id", UUID.randomUUID().toString())
                .setOnInsert("createdAt", now)
                .set("userId", userId)
                .set("artifactKey", spec.artifactKey())
                .set("capability", capability)
                .set("correlationType", correlationType)
                .set("correlationId", correlationId)
                .set("status", AiArtifactStatus.PENDING)
                .set("payloadKind", storeAsBinary ? AiArtifactPayloadKind.BINARY : AiArtifactPayloadKind.INLINE)
                .set("codecId", spec.codec().id())
                .set("consumerId", spec.consumerId())
                .set("contentType", payload.contentType())
                .set("payloadSize", payload.size())
                .set("gridFsId", gridFsId)
                .set("inlinePayload", inlinePayload)
                .set("producedByModelKey", selection != null ? selection.model().getKey() : null)
                .set("producedByTier", selection != null ? selection.model().getTier() : null)
                .set("usedFallback", selection != null && selection.usedFallback())
                .set("creditCost", selection != null && selection.reservation() != null
                        ? selection.reservation().cost() : 0)
                .set("producingJobId", producingJobId)
                .set("recoveryAttempts", 0)
                .set("lastError", null)
                .set("updatedAt", now)
                // Reviving a slot whose previous payload was consumed: the TTL must come back off,
                // or this newly staged payload would inherit the old row's expiry.
                .unset("expiresAt")
                .unset("consumedAt")
                .unset("leaseUntil");

        AiArtifact staged;
        try {
            staged = mongoTemplate.findAndModify(
                    query(where("userId").is(userId).and("artifactKey").is(spec.artifactKey())),
                    update,
                    FindAndModifyOptions.options().upsert(true).returnNew(true),
                    AiArtifact.class);
        } catch (DuplicateKeyException e) {
            // Two concurrent upserts raced on the same key. Unlike AiRequestQueueService#createJob,
            // where a duplicate means a genuine idempotency conflict worth surfacing as a 409, here
            // the payload did get stored — so recover the winning row rather than failing the call.
            staged = artifactRepository.findByUserIdAndArtifactKey(userId, spec.artifactKey()).orElse(null);
        }

        // Only after the row points at the new blob: crashing before this leaves a garbage blob for
        // the GC pass, whereas deleting first could leave a PENDING row pointing at nothing.
        if (previousGridFsId != null && !previousGridFsId.equals(gridFsId)) {
            deleteBlob(previousGridFsId);
        }

        if (staged != null) {
            log.debug("event=ai_artifact_staged artifactId={} artifactKey={} bytes={}",
                    staged.getId(), staged.getArtifactKey(), staged.getPayloadSize());
        }
        return staged;
    }

    /**
     * Marks the dependent work as successfully completed and retires the payload.
     * <p>
     * The status flip happens first and is guarded on {@code PENDING}, which makes it a
     * single-winner election: only the caller that wins it goes on to delete the blob. Crashing
     * between the two steps leaves a {@code CONSUMED} row still carrying a {@code gridFsId},
     * which is exactly what the sweeper's GC pass looks for. The reverse order would risk a
     * {@code PENDING} row whose payload is gone, which the sweeper would retry forever.
     *
     * @param artifactId the artifact to retire
     * @return true if this caller won the transition; false if another already consumed it
     */
    public boolean markConsumed(String artifactId) {
        if (artifactId == null) {
            return false;
        }
        Instant now = Instant.now();
        UpdateResult result = mongoTemplate.updateFirst(
                query(where("_id").is(artifactId).and("status").is(AiArtifactStatus.PENDING)),
                new Update()
                        .set("status", AiArtifactStatus.CONSUMED)
                        .set("consumedAt", now)
                        .set("updatedAt", now)
                        .set("expiresAt", now.plus(properties.getConsumedRetention()))
                        .unset("leaseUntil"),
                AiArtifact.class);

        if (result.getModifiedCount() == 0) {
            log.debug("event=ai_artifact_consume_skipped artifactId={} reason=already_consumed", artifactId);
            return false;
        }

        releasePayload(artifactId);
        return true;
    }

    /**
     * Records a failed recovery attempt, releasing the lease so a later sweep can retry.
     * Once the attempt budget is exhausted the artifact is abandoned: its payload is dropped and
     * the row is kept, with {@code lastError}, for diagnostics until its retention expires.
     *
     * @param artifactId the artifact whose recovery failed
     * @param errorMessage the failure to record
     */
    public void markRecoveryFailure(String artifactId, String errorMessage) {
        Instant now = Instant.now();
        AiArtifact updated = mongoTemplate.findAndModify(
                query(where("_id").is(artifactId)),
                new Update()
                        .inc("recoveryAttempts", 1)
                        .set("lastError", errorMessage)
                        .set("updatedAt", now)
                        .unset("leaseUntil"),
                FindAndModifyOptions.options().returnNew(true),
                AiArtifact.class);

        if (updated == null
                || updated.getRecoveryAttempts() < properties.getSweeper().getMaxRecoveryAttempts()
                || updated.getStatus() != AiArtifactStatus.PENDING) {
            return;
        }

        log.error("event=ai_artifact_abandoned artifactId={} artifactKey={} recoveryAttempts={} lastError={}",
                updated.getId(), updated.getArtifactKey(), updated.getRecoveryAttempts(), errorMessage);
        mongoTemplate.updateFirst(
                query(where("_id").is(artifactId).and("status").is(AiArtifactStatus.PENDING)),
                new Update()
                        .set("status", AiArtifactStatus.ABANDONED)
                        .set("updatedAt", now)
                        .set("expiresAt", now.plus(properties.getAbandonedRetention())),
                AiArtifact.class);
        releasePayload(artifactId);
    }

    /**
     * Decodes a stored payload without knowing its type at compile time — the recovery path,
     * which starts from a database row carrying only a {@code codecId}.
     *
     * @param artifact the artifact to load
     * @return the artifact with its decoded payload, or empty if no codec is registered for it
     *         or the payload cannot be read
     */
    public Optional<LoadedArtifact> load(AiArtifact artifact) {
        Optional<AiArtifactCodec<?>> codec = codecRegistry.find(artifact.getCodecId());
        if (codec.isEmpty()) {
            log.warn("event=ai_artifact_decode_failed artifactId={} codecId={} reason=no_codec",
                    artifact.getId(), artifact.getCodecId());
            return Optional.empty();
        }
        try {
            Object value = codec.get().decode(readPayload(artifact));
            return Optional.of(new LoadedArtifact(artifact, value));
        } catch (Exception e) {
            log.warn("event=ai_artifact_decode_failed artifactId={} codecId={} errorType={}",
                    artifact.getId(), artifact.getCodecId(), e.getClass().getSimpleName(), e);
            return Optional.empty();
        }
    }

    /**
     * Pending artifacts whose dependent work never completed and which no live request is
     * currently holding.
     *
     * @param now the sweep's reference time
     * @param limit maximum artifacts to return
     * @return candidates for recovery, oldest first
     */
    public List<AiArtifact> findOrphans(Instant now, int limit) {
        Instant cutoff = now.minusMillis(properties.getSweeper().getOrphanAgeMs());
        Query query = query(where("status").is(AiArtifactStatus.PENDING)
                .and("createdAt").lt(cutoff)
                .and("recoveryAttempts").lt(properties.getSweeper().getMaxRecoveryAttempts())
                .orOperator(
                        where("leaseUntil").exists(false),
                        where("leaseUntil").is(null),
                        where("leaseUntil").lt(now)))
                .with(org.springframework.data.domain.Sort.by("createdAt").ascending())
                .limit(limit);
        return mongoTemplate.find(query, AiArtifact.class);
    }

    /**
     * Atomically claims an artifact for recovery, re-checking the lease so two instances
     * sweeping at once cannot both take it.
     *
     * @param artifactId the artifact to claim
     * @return the claimed artifact, or empty if another worker won it
     */
    public Optional<AiArtifact> acquireLease(String artifactId) {
        Instant now = Instant.now();
        Criteria unleased = where("_id").is(artifactId)
                .and("status").is(AiArtifactStatus.PENDING)
                .orOperator(
                        where("leaseUntil").exists(false),
                        where("leaseUntil").is(null),
                        where("leaseUntil").lt(now));

        AiArtifact claimed = mongoTemplate.findAndModify(
                query(unleased),
                new Update()
                        .set("leaseUntil", now.plusMillis(properties.getLeaseTtlMs()))
                        .set("updatedAt", now),
                FindAndModifyOptions.options().returnNew(true),
                AiArtifact.class);
        return Optional.ofNullable(claimed);
    }

    /**
     * Terminal artifacts whose payload blob still needs collecting, because a process died
     * between the status flip and the blob delete.
     *
     * @param limit maximum rows to return
     * @return rows still carrying a {@code gridFsId}
     */
    public List<AiArtifact> findCollectableBlobs(int limit) {
        return artifactRepository.findByStatusInAndGridFsIdNotNull(
                List.of(AiArtifactStatus.CONSUMED, AiArtifactStatus.ABANDONED),
                PageRequest.of(0, limit));
    }

    /**
     * Deletes an artifact's payload and clears the reference, so the row no longer claims to
     * hold one.
     *
     * @param artifactId the artifact whose payload should be dropped
     */
    public void releasePayload(String artifactId) {
        AiArtifact artifact = mongoTemplate.findOne(query(where("_id").is(artifactId)), AiArtifact.class);
        if (artifact == null) {
            return;
        }
        deleteBlob(artifact.getGridFsId());
        mongoTemplate.updateFirst(
                query(where("_id").is(artifactId)),
                new Update()
                        .unset("gridFsId")
                        .unset("inlinePayload")
                        .set("payloadSize", 0)
                        .set("updatedAt", Instant.now()),
                AiArtifact.class);
    }

    private String storeBlob(Long userId, String artifactKey, AiArtifactPayload payload) {
        try (InputStream content = new ByteArrayInputStream(payload.asBytes())) {
            Document metadata = new Document("userId", userId).append("artifactKey", artifactKey);
            ObjectId id = gridFsTemplate.store(
                    content,
                    "ai-artifact/" + userId + "/" + artifactKey,
                    payload.contentType(),
                    metadata);
            return id.toHexString();
        } catch (IOException e) {
            // ByteArrayInputStream#close cannot actually fail; rethrow so staging reports it.
            throw new IllegalStateException("Failed to store AI artifact payload", e);
        }
    }

    private void deleteBlob(String gridFsId) {
        if (gridFsId == null) {
            return;
        }
        try {
            gridFsTemplate.delete(query(where("_id").is(new ObjectId(gridFsId))));
        } catch (Exception e) {
            // A blob we cannot delete is wasted storage, not a correctness problem, and the GC
            // pass will try again while the row still references it.
            log.warn("event=ai_artifact_blob_delete_failed gridFsId={} errorType={}", gridFsId, e.getClass().getSimpleName(), e);
        }
    }

    private AiArtifactPayload readPayload(AiArtifact artifact) throws IOException {
        if (artifact.getPayloadKind() == AiArtifactPayloadKind.INLINE && artifact.getGridFsId() == null) {
            String text = artifact.getInlinePayload() == null ? "" : artifact.getInlinePayload();
            return AiArtifactPayload.inline(artifact.getContentType(), text);
        }

        if (artifact.getGridFsId() == null) {
            throw new IllegalStateException("AI artifact " + artifact.getId() + " has no stored payload");
        }

        GridFSFile file = gridFsTemplate.findOne(query(where("_id").is(new ObjectId(artifact.getGridFsId()))));
        if (file == null) {
            throw new IllegalStateException("AI artifact blob " + artifact.getGridFsId() + " is missing");
        }

        try (InputStream in = gridFsTemplate.getResource(file).getInputStream()) {
            byte[] bytes = in.readAllBytes();
            // An inline payload promoted to GridFS must round-trip as the kind its codec expects.
            return artifact.getPayloadKind() == AiArtifactPayloadKind.INLINE
                    ? AiArtifactPayload.inline(artifact.getContentType(), new String(bytes, StandardCharsets.UTF_8))
                    : AiArtifactPayload.binary(artifact.getContentType(), bytes);
        }
    }
}
