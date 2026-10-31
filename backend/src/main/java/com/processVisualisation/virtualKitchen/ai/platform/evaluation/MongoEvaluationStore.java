package com.processVisualisation.virtualKitchen.ai.platform.evaluation;

import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.ImageEvaluation;
import com.processVisualisation.virtualKitchen.ai.platform.evaluation.model.SetEvaluation;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.springframework.data.mongodb.core.query.Criteria.where;

@Component
public class MongoEvaluationStore implements EvaluationStore {

    private final MongoTemplate mongoTemplate;

    public MongoEvaluationStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<ImageEvaluation> findImage(String id) {
        return Optional.ofNullable(mongoTemplate.findById(id, ImageEvaluation.class));
    }

    @Override
    public List<ImageEvaluation> findImagesBySetKeys(Collection<String> setKeys) {
        if (setKeys.isEmpty()) return List.of();
        return mongoTemplate.find(Query.query(where("setKey").in(setKeys)), ImageEvaluation.class);
    }

    @Override
    public boolean insertImage(ImageEvaluation evaluation) {
        try {
            mongoTemplate.insert(evaluation);
            return true;
        } catch (DuplicateKeyException alreadyThere) {
            return false;
        }
    }

    @Override
    public boolean replaceImage(ImageEvaluation evaluation, long expectedRevision) {
        Query query = Query.query(where("_id").is(evaluation.getId()).and("revision").is(expectedRevision));
        return mongoTemplate.findAndReplace(query, evaluation) != null;
    }

    @Override
    public Optional<SetEvaluation> findSet(String setKey) {
        return Optional.ofNullable(mongoTemplate.findById(setKey, SetEvaluation.class));
    }

    @Override
    public List<SetEvaluation> findSets(Collection<String> setKeys) {
        if (setKeys.isEmpty()) return List.of();
        return mongoTemplate.find(Query.query(where("_id").in(setKeys)), SetEvaluation.class);
    }

    @Override
    public boolean insertSet(SetEvaluation evaluation) {
        try {
            mongoTemplate.insert(evaluation);
            return true;
        } catch (DuplicateKeyException alreadyThere) {
            return false;
        }
    }

    @Override
    public boolean replaceSet(SetEvaluation evaluation, long expectedRevision) {
        Query query = Query.query(where("_id").is(evaluation.getId()).and("revision").is(expectedRevision));
        return mongoTemplate.findAndReplace(query, evaluation) != null;
    }
}
