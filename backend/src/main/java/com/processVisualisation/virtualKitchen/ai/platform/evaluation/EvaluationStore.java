package com.processVisualisation.virtualKitchen.ai.platform.evaluation;

import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.ImageEvaluation;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.SetEvaluation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Persistence of human evaluations. Replacements are compare-and-set on {@code revision}. */
public interface EvaluationStore {

    Optional<ImageEvaluation> findImage(String id);

    List<ImageEvaluation> findImagesBySetKeys(Collection<String> setKeys);

    /** False when a record with the same id already exists. */
    boolean insertImage(ImageEvaluation evaluation);

    /** False when the stored revision is no longer {@code expectedRevision}. */
    boolean replaceImage(ImageEvaluation evaluation, long expectedRevision);

    Optional<SetEvaluation> findSet(String setKey);

    List<SetEvaluation> findSets(Collection<String> setKeys);

    boolean insertSet(SetEvaluation evaluation);

    boolean replaceSet(SetEvaluation evaluation, long expectedRevision);
}
