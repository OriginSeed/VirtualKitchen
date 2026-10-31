package com.processVisualisation.virtualKitchen.ai.platform.evaluation;

import com.processVisualisation.virtualKitchen.ai.platform.analytics.model.AiRequestAnalytics;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationRubric.DefectTag;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationRubric.ReasonTag;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationScoring.SetScores;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.EvaluationSets.ImageSet;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.ImageEvaluation;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.SetEvaluation;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.Verdict;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Write side of human evaluation. Every save is compare-and-set on the record's {@code revision}: the client sends
 * the revision it loaded ({@code null} to create), and a save over someone else's newer edit is refused with
 * {@link ConflictException} instead of silently overwriting it. Saving an image score recomputes its set's scores.
 */
@Service
public class EvaluationService {

    static final int MAX_NOTE_CHARS = 2_000;
    private static final int RECOMPUTE_TRIES = 8;

    private final EvaluationStore store;
    private final EvaluationQueryService queryService;
    private final Clock clock;

    public EvaluationService(EvaluationStore store, EvaluationQueryService queryService) {
        this(store, queryService, Clock.systemUTC());
    }

    EvaluationService(EvaluationStore store, EvaluationQueryService queryService, Clock clock) {
        this.store = store;
        this.queryService = queryService;
        this.clock = clock;
    }

    /** The record changed since the client loaded it. */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /** No such image set, or the image is not part of it. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public record ImageScoreInput(Integer consistency, Integer stepCorrectness, Integer stateContinuity,
                                  Integer realism, List<String> defectTags, String note, Long revision) {
    }

    public record VerdictInput(String verdict, List<String> reasonTags, String note, Long revision) {
    }

    public record ImageSaveResult(ImageEvaluation image, SetEvaluation set) {
    }

    public ImageSaveResult saveImage(Long adminId, String setKey, String requestId, ImageScoreInput input) {
        ImageSet set = queryService.loadSet(setKey)
                .orElseThrow(() -> new NotFoundException("No image set " + setKey));
        AiRequestAnalytics row = set.images().stream().filter(r -> r.getId().equals(requestId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Image " + requestId + " is not part of set " + setKey));

        Integer consistency = rating("consistency", input.consistency());
        Integer stepCorrectness = rating("stepCorrectness", input.stepCorrectness());
        Integer stateContinuity = rating("stateContinuity", input.stateContinuity());
        Integer realism = rating("realism", input.realism());
        List<DefectTag> tags = tags(DefectTag.class, input.defectTags());
        String note = note(input.note());
        Instant now = clock.instant();

        Optional<ImageEvaluation> existing = store.findImage(requestId);
        ImageEvaluation evaluation;
        if (input.revision() == null) {
            if (existing.isPresent()) throw new ConflictException("This image was scored meanwhile; reload to edit it.");
            evaluation = new ImageEvaluation();
            evaluation.setId(requestId);
            evaluation.setEvaluatedBy(adminId);
            evaluation.setEvaluatedAt(now);
        } else {
            evaluation = existing.filter(e -> e.getRevision() == input.revision())
                    .orElseThrow(() -> new ConflictException("This image's scores changed meanwhile; reload and retry."));
            evaluation.getEdits().add(snapshot(evaluation));
            if (evaluation.getEdits().size() > ImageEvaluation.MAX_EDITS) evaluation.getEdits().remove(0);
        }
        evaluation.setSetKey(setKey);
        evaluation.setRecipeId(row.getRecipeId());
        evaluation.setStepId(row.getStepId());
        evaluation.setModelKey(row.getResolvedModelKey());
        evaluation.setProvider(row.getFinalProvider());
        evaluation.setTier(row.getModelTier());
        evaluation.setConsistency(consistency);
        evaluation.setStepCorrectness(stepCorrectness);
        evaluation.setStateContinuity(stateContinuity);
        evaluation.setRealism(realism);
        evaluation.setDefectTags(tags);
        evaluation.setNote(note);
        evaluation.setImageScore(EvaluationScoring.imageScore(consistency, stepCorrectness, stateContinuity, realism, tags));
        evaluation.setRubricVersion(EvaluationRubric.VERSION);
        evaluation.setUpdatedBy(adminId);
        evaluation.setUpdatedAt(now);

        long expected = evaluation.getRevision();
        evaluation.setRevision(expected + 1);
        boolean saved = input.revision() == null ? store.insertImage(evaluation) : store.replaceImage(evaluation, expected);
        if (!saved) throw new ConflictException("This image's scores changed meanwhile; reload and retry.");

        return new ImageSaveResult(evaluation, recompute(set, adminId, now));
    }

    public SetEvaluation saveVerdict(Long adminId, String setKey, VerdictInput input) {
        ImageSet set = queryService.loadSet(setKey)
                .orElseThrow(() -> new NotFoundException("No image set " + setKey));
        Verdict verdict = input.verdict() == null || input.verdict().isBlank() ? null : parse(Verdict.class, input.verdict());
        List<ReasonTag> reasons = tags(ReasonTag.class, input.reasonTags());
        String note = note(input.note());
        Instant now = clock.instant();

        Optional<SetEvaluation> existing = store.findSet(setKey);
        SetEvaluation evaluation;
        if (input.revision() == null) {
            if (existing.isPresent()) throw new ConflictException("This set was evaluated meanwhile; reload to edit it.");
            evaluation = newSet(set, adminId, now);
        } else {
            evaluation = existing.filter(e -> e.getRevision() == input.revision())
                    .orElseThrow(() -> new ConflictException("This set's evaluation changed meanwhile; reload and retry."));
        }
        evaluation.setVerdict(verdict);
        evaluation.setReasonTags(reasons);
        evaluation.setNote(note);
        applyScores(evaluation, set, adminId, now);

        long expected = evaluation.getRevision();
        evaluation.setRevision(expected + 1);
        boolean saved = input.revision() == null ? store.insertSet(evaluation) : store.replaceSet(evaluation, expected);
        if (!saved) throw new ConflictException("This set's evaluation changed meanwhile; reload and retry.");
        return evaluation;
    }

    /** Recomputes the set's scores from its image scores, retrying on concurrent writes (no user input is lost). */
    private SetEvaluation recompute(ImageSet set, Long adminId, Instant now) {
        for (int i = 0; i < RECOMPUTE_TRIES; i++) {
            Optional<SetEvaluation> existing = store.findSet(set.setKey());
            SetEvaluation evaluation = existing.orElseGet(() -> newSet(set, adminId, now));
            applyScores(evaluation, set, adminId, now);
            long expected = evaluation.getRevision();
            evaluation.setRevision(expected + 1);
            boolean saved = existing.isEmpty() ? store.insertSet(evaluation) : store.replaceSet(evaluation, expected);
            if (saved) return evaluation;
        }
        throw new ConflictException("The set's scores are being updated concurrently; reload and retry.");
    }

    private void applyScores(SetEvaluation evaluation, ImageSet set, Long adminId, Instant now) {
        SetScores scores = EvaluationScoring.setScores(store.findImagesBySetKeys(List.of(set.setKey())), evaluation.getVerdict());
        evaluation.setRecipeId(set.recipeId());
        evaluation.setModelKey(set.modelKey());
        evaluation.setMeanScore(scores.meanScore());
        evaluation.setWorstScore(scores.worstScore());
        evaluation.setRecipeScore(scores.recipeScore());
        evaluation.setScoredImages(scores.scoredImages());
        evaluation.setTotalImages(set.images().size());
        evaluation.setHardFail(scores.hardFail());
        evaluation.setAccepted(scores.accepted());
        evaluation.setRubricVersion(EvaluationRubric.VERSION);
        evaluation.setUpdatedBy(adminId);
        evaluation.setUpdatedAt(now);
    }

    private static SetEvaluation newSet(ImageSet set, Long adminId, Instant now) {
        SetEvaluation evaluation = new SetEvaluation();
        evaluation.setId(set.setKey());
        evaluation.setEvaluatedBy(adminId);
        evaluation.setEvaluatedAt(now);
        return evaluation;
    }

    private static ImageEvaluation.Edit snapshot(ImageEvaluation evaluation) {
        ImageEvaluation.Edit edit = new ImageEvaluation.Edit();
        edit.setConsistency(evaluation.getConsistency());
        edit.setStepCorrectness(evaluation.getStepCorrectness());
        edit.setStateContinuity(evaluation.getStateContinuity());
        edit.setRealism(evaluation.getRealism());
        edit.setDefectTags(evaluation.getDefectTags() == null ? null : new ArrayList<>(evaluation.getDefectTags()));
        edit.setNote(evaluation.getNote());
        edit.setImageScore(evaluation.getImageScore());
        edit.setBy(evaluation.getUpdatedBy());
        edit.setAt(evaluation.getUpdatedAt());
        return edit;
    }

    static Integer rating(String field, Integer value) {
        if (value != null && (value < 1 || value > 5)) {
            throw new IllegalArgumentException(field + " must be between 1 and 5");
        }
        return value;
    }

    static String note(String note) {
        if (note == null || note.isBlank()) return null;
        String trimmed = note.trim();
        if (trimmed.length() > MAX_NOTE_CHARS) {
            throw new IllegalArgumentException("Note is longer than " + MAX_NOTE_CHARS + " characters");
        }
        return trimmed;
    }

    static <E extends Enum<E>> List<E> tags(Class<E> type, List<String> values) {
        if (values == null) return new ArrayList<>();
        LinkedHashSet<E> tags = new LinkedHashSet<>();
        for (String value : values) tags.add(parse(type, value));
        return new ArrayList<>(tags);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim());
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown " + type.getSimpleName() + ": " + value);
        }
    }
}
