package com.processVisualisation.virtualKitchen.ai.recipe.narration;

import com.processVisualisation.virtualKitchen.ai.recipe.narration.model.StepNarration;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.model.StepNarrationStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * {@link StepNarrationStore} on MongoDB. The claim is a conditional {@code findAndModify}, plus an
 * insert guarded by the unique {@code narrationKey} index for the first claim of a step. Either
 * way exactly one concurrent caller wins. This mirrors the {@code activeKey} guard the
 * visualization jobs use.
 */
@Component
public class MongoStepNarrationStore implements StepNarrationStore {

    private final MongoTemplate mongoTemplate;
    private final StepNarrationRepository repository;

    public MongoStepNarrationStore(MongoTemplate mongoTemplate, StepNarrationRepository repository) {
        this.mongoTemplate = mongoTemplate;
        this.repository = repository;
    }

    @Override
    public Optional<StepNarration> find(String narrationKey) {
        return repository.findByNarrationKey(narrationKey);
    }

    @Override
    public List<StepNarration> findAll(Collection<String> narrationKeys) {
        return narrationKeys.isEmpty() ? List.of() : repository.findByNarrationKeyIn(narrationKeys);
    }

    @Override
    public Optional<StepNarration> claim(Claim claim) {
        List<Criteria> refused = new ArrayList<>();
        refused.add(where("status").is(StepNarrationStatus.GENERATING)
                .and("sourceTextHash").is(claim.sourceTextHash())
                .and("leaseUntil").gt(claim.now()));
        if (!claim.force()) {
            refused.add(where("status").is(StepNarrationStatus.READY)
                    .and("sourceTextHash").is(claim.sourceTextHash()));
        }
        Criteria claimable = where("narrationKey").is(claim.narrationKey())
                .norOperator(refused.toArray(new Criteria[0]));

        StepNarration claimed = mongoTemplate.findAndModify(
                query(claimable),
                new Update()
                        .set("status", StepNarrationStatus.GENERATING)
                        .set("sourceTextHash", claim.sourceTextHash())
                        .set("generationToken", claim.token())
                        .set("leaseUntil", claim.leaseUntil())
                        .set("generatedByUserId", claim.userId())
                        .set("processId", claim.processId())
                        .set("updatedAt", claim.now())
                        .inc("attempts", 1)
                        .unset("failureReason")
                        .unset("failedAt"),
                FindAndModifyOptions.options().returnNew(true),
                StepNarration.class);
        if (claimed != null) {
            return Optional.of(claimed);
        }
        if (repository.findByNarrationKey(claim.narrationKey()).isPresent()) {
            return Optional.empty();
        }
        return insertFirstClaim(claim);
    }

    private Optional<StepNarration> insertFirstClaim(Claim claim) {
        StepNarration narration = new StepNarration();
        narration.setId(UUID.randomUUID().toString());
        narration.setNarrationKey(claim.narrationKey());
        narration.setRecipeId(claim.recipeId());
        narration.setProcessId(claim.processId());
        narration.setStepId(claim.stepId());
        narration.setStatus(StepNarrationStatus.GENERATING);
        narration.setSourceTextHash(claim.sourceTextHash());
        narration.setGenerationToken(claim.token());
        narration.setLeaseUntil(claim.leaseUntil());
        narration.setGeneratedByUserId(claim.userId());
        narration.setAttempts(1);
        narration.setCreatedAt(claim.now());
        narration.setUpdatedAt(claim.now());
        try {
            return Optional.of(mongoTemplate.insert(narration));
        } catch (DuplicateKeyException e) {
            // A concurrent first claim for the same step won the insert race.
            return Optional.empty();
        }
    }

    @Override
    public Optional<StepNarration> commitReady(String narrationKey, String token, ReadyAudio audio) {
        StepNarration previous = mongoTemplate.findAndModify(
                query(where("narrationKey").is(narrationKey).and("generationToken").is(token)),
                new Update()
                        .set("status", StepNarrationStatus.READY)
                        .set("audioUrl", audio.audioUrl())
                        .set("storageBackend", audio.storageBackend())
                        .set("storagePath", audio.storagePath())
                        .set("mimeType", audio.mimeType())
                        .set("format", audio.format())
                        .set("durationMs", audio.durationMs())
                        .set("sizeBytes", audio.sizeBytes())
                        .set("provider", audio.provider())
                        .set("modelKey", audio.modelKey())
                        .set("providerModelId", audio.providerModelId())
                        .set("voice", audio.voice())
                        .set("languageCode", audio.languageCode())
                        .set("generatedAt", audio.generatedAt())
                        .set("updatedAt", audio.generatedAt())
                        .unset("leaseUntil")
                        .unset("failureReason")
                        .unset("failedAt"),
                FindAndModifyOptions.options().returnNew(false),
                StepNarration.class);
        return Optional.ofNullable(previous);
    }

    @Override
    public boolean markFailed(String narrationKey, String token, String reason, Instant failedAt) {
        return mongoTemplate.updateFirst(
                query(where("narrationKey").is(narrationKey).and("generationToken").is(token)),
                new Update()
                        .set("status", StepNarrationStatus.FAILED)
                        .set("failureReason", reason)
                        .set("failedAt", failedAt)
                        .set("updatedAt", failedAt)
                        .unset("leaseUntil"),
                StepNarration.class).getModifiedCount() > 0;
    }

    @Override
    public List<StepNarration> findReadyByRecipeId(Long recipeId) {
        return mongoTemplate.find(
                query(where("recipeId").is(recipeId).and("status").is(StepNarrationStatus.READY)), StepNarration.class);
    }

    @Override
    public Optional<StepNarration> findReadyByAudioUrl(String audioUrl) {
        return Optional.ofNullable(mongoTemplate.findOne(
                query(where("audioUrl").is(audioUrl).and("status").is(StepNarrationStatus.READY)), StepNarration.class));
    }

    @Override
    public boolean insertIfAbsent(StepNarration narration) {
        try {
            mongoTemplate.insert(narration);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public Optional<StepNarration> delete(String narrationKey) {
        return Optional.ofNullable(mongoTemplate.findAndRemove(
                query(where("narrationKey").is(narrationKey)), StepNarration.class));
    }
}
