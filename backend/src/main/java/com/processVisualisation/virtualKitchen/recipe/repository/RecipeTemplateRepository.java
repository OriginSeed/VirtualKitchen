package com.processVisualisation.virtualKitchen.recipe.repository;

import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import com.processVisualisation.virtualKitchen.recipe.model.Visibility;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data MongoDB repository for {@link RecipeTemplate} documents, which represent the
 * reusable, user-authored recipe/process templates (name, description, owner, visibility) that
 * template steps, executions, and copies are built from.
 */
public interface RecipeTemplateRepository extends MongoRepository<RecipeTemplate, Long> {

    /**
     * Fetches all recipe templates owned by a given user.
     *
     * @param createdBy the id of the owning user to filter by
     * @return the templates created by that user
     */
    List<RecipeTemplate> findByCreatedBy(Long createdBy);

    /**
     * Fetches all recipe templates with the given visibility that were NOT created by the given
     * user, used to list globally shared/public recipes available to a user.
     *
     * @param visibility the visibility to filter by (e.g. {@code PUBLIC})
     * @param createdBy  the id of the user to exclude as owner
     * @return the matching templates owned by other users
     */
    List<RecipeTemplate> findByVisibilityAndCreatedByNot(Visibility visibility, Long createdBy);

    /**
     * Atomically sets a recipe's {@code mainProcessId}, but only if it has none yet, so two
     * concurrent "ensure a MAIN process" requests can never both link their own MAIN.
     *
     * @return the number of recipes updated: 1 if this call linked the process, 0 if the recipe
     *         already had a main process (or doesn't exist)
     */
    @Query("{ '_id': ?0, 'mainProcessId': null }")
    @Update("{ '$set': { 'mainProcessId': ?1 } }")
    long linkMainProcessIfUnset(Long recipeId, Long processId);

    /**
     * Atomically claims the next process revision of a recipe, but only if its current
     * {@code processRevision} still equals {@code expectedRevision} (optimistic concurrency for the
     * recipe-level process save). Use {@link #claimInitialProcessRevision} when the expected
     * revision is 0, since older documents have no {@code processRevision} field at all.
     *
     * @return 1 if the revision was claimed, 0 if the recipe was saved by someone else since
     *         {@code expectedRevision} (or doesn't exist)
     */
    @Query("{ '_id': ?0, 'processRevision': ?1 }")
    @Update("{ '$inc': { 'processRevision': 1 } }")
    long claimProcessRevision(Long recipeId, Long expectedRevision);

    /**
     * {@link #claimProcessRevision} for an expected revision of 0: matches a recipe whose
     * {@code processRevision} is 0, null or absent (documents written before revisions existed).
     */
    @Query("{ '_id': ?0, '$or': [ { 'processRevision': 0 }, { 'processRevision': null } ] }")
    @Update("{ '$inc': { 'processRevision': 1 } }")
    long claimInitialProcessRevision(Long recipeId);

    /**
     * Unconditionally bumps a recipe's process revision — for a batch save sent without a base
     * revision (older clients), so clients that do send one still notice the change.
     */
    @Query("{ '_id': ?0 }")
    @Update("{ '$inc': { 'processRevision': 1 } }")
    long incrementProcessRevision(Long recipeId);

    /**
     * Best-effort undo of a claim whose save then failed, so the client's retry from the same base
     * revision isn't rejected as a conflict with itself. Only applies while nobody else has claimed
     * a newer revision in the meantime.
     */
    @Query("{ '_id': ?0, 'processRevision': ?1 }")
    @Update("{ '$inc': { 'processRevision': -1 } }")
    long releaseProcessRevision(Long recipeId, Long claimedRevision);

    /**
     * Marks a recipe as changed now — when one of its processes (its steps) is saved, which is not a save of the
     * recipe document itself — so "recently updated" reflects editing the recipe's steps. Leaves {@code createdAt}
     * alone.
     */
    @Query("{ '_id': ?0 }")
    @Update("{ '$set': { 'updatedAt': ?1 } }")
    long touchUpdatedAt(Long recipeId, LocalDateTime updatedAt);
}
