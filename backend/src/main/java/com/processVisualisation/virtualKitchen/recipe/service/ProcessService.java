package com.processVisualisation.virtualKitchen.recipe.service;

import com.processVisualisation.virtualKitchen.common.persistence.SequenceGeneratorService;
import com.processVisualisation.virtualKitchen.recipe.dto.ProcessBatchItemDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.ProcessBatchUpdateResponseDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.ProcessRequestDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.ProcessResponseDTO;
import com.processVisualisation.virtualKitchen.recipe.dto.ProcessUpdateDTO;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeRevisionConflictException;
import com.processVisualisation.virtualKitchen.recipe.mapper.ProcessMapper;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessType;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import com.processVisualisation.virtualKitchen.recipe.model.Visibility;
import com.processVisualisation.virtualKitchen.recipe.repository.ProcessRepository;
import com.processVisualisation.virtualKitchen.recipe.repository.RecipeTemplateRepository;
import com.processVisualisation.virtualKitchen.recipe.validation.ProcessValidationException;
import com.processVisualisation.virtualKitchen.recipe.validation.ProcessValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Process (flow graph) CRUD, backed by MongoDB via
 * {@link ProcessRepository}. Every operation is scoped to the recipe named
 * in its {@code recipeId} parameter: a process that exists but belongs to a
 * different recipe is treated as not found ({@link #requireBelongsToRecipe}),
 * matching the "recipe-scoped process references only" Phase 1 rule that
 * {@link ProcessValidator} also enforces at the graph level.
 */
@Service
public class ProcessService {

    private static final Logger log = LoggerFactory.getLogger(ProcessService.class);

    private static final String COPY_SUFFIX = " (copy)";

    private final ProcessRepository processRepository;
    private final RecipeTemplateRepository recipeTemplateRepository;
    private final SequenceGeneratorService sequenceGeneratorService;
    private final ProcessValidator processValidator;
    private final ProcessMapper processMapper;

    public ProcessService(
            ProcessRepository processRepository,
            RecipeTemplateRepository recipeTemplateRepository,
            SequenceGeneratorService sequenceGeneratorService,
            ProcessValidator processValidator,
            ProcessMapper processMapper
    ) {
        this.processRepository = processRepository;
        this.recipeTemplateRepository = recipeTemplateRepository;
        this.sequenceGeneratorService = sequenceGeneratorService;
        this.processValidator = processValidator;
        this.processMapper = processMapper;
    }

    /**
     * Creates a new process (MAIN or SUBPROCESS, per {@code dto.getType()})
     * under a recipe, after verifying the requesting user owns it and
     * validating the result via {@code ProcessValidator} (which rejects,
     * among other things, a second MAIN process for the same recipe).
     *
     * @param recipeId the id of the owning recipe
     * @param userId   the id of the user requesting the creation, used for ownership verification
     * @param dto      the process type/name/description to persist
     * @return the created process
     */
    public ProcessResponseDTO create(Long recipeId, Long userId, ProcessRequestDTO dto) {
        requireOwnedRecipe(recipeId, userId);

        Process process = new Process();
        process.setId(sequenceGeneratorService.generateSequence(Process.SEQUENCE_NAME));
        process.setType(dto.getType());
        process.setRecipeId(recipeId);
        process.setName(dto.getName());
        process.setDescription(dto.getDescription());

        processValidator.validateOrThrow(process);
        Process saved = processRepository.save(process);
        touchRecipe(recipeId);
        log.info("event=process_created recipeId={} processId={} type={} userId={}",
                recipeId, saved.getId(), saved.getType(), userId);
        return processMapper.toDTO(saved);
    }

    /**
     * Retrieves a single process by id, scoped to a recipe.
     *
     * @param recipeId         the id of the recipe the process is expected to belong to
     * @param processId        the id of the process to fetch
     * @param requestingUserId the id of the requesting user, or null if unauthenticated; used for
     *                         visibility on a private recipe
     * @return the matching process
     */
    public ProcessResponseDTO get(Long recipeId, Long processId, Long requestingUserId) {
        requireAccessibleRecipe(recipeId, requestingUserId);
        Process process = findProcessOrThrow(processId);
        requireBelongsToRecipe(process, recipeId);
        return processMapper.toDTO(process);
    }

    /**
     * Retrieves every process (MAIN and any SUBPROCESS documents) belonging to a recipe.
     *
     * @param recipeId         the id of the owning recipe
     * @param requestingUserId the id of the requesting user, or null if unauthenticated; used for
     *                         visibility on a private recipe
     * @return the recipe's processes
     */
    public List<ProcessResponseDTO> listByRecipe(Long recipeId, Long requestingUserId) {
        requireAccessibleRecipe(recipeId, requestingUserId);
        return processRepository.findByRecipeId(recipeId)
                .stream()
                .map(processMapper::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Updates a process's name/description and replaces its node/edge graph
     * and viewport, after verifying the requesting user owns the recipe and
     * validating the result via {@code ProcessValidator}.
     *
     * @param recipeId  the id of the recipe the process is expected to belong to
     * @param processId the id of the process to update
     * @param userId    the id of the user requesting the update, used for ownership verification
     * @param dto       the replacement name/description/nodes/edges/viewport
     * @return the updated process
     */
    public ProcessResponseDTO update(Long recipeId, Long processId, Long userId, ProcessUpdateDTO dto) {
        requireOwnedRecipe(recipeId, userId);
        Process process = findProcessOrThrow(processId);
        requireBelongsToRecipe(process, recipeId);

        process.setName(dto.getName());
        process.setDescription(dto.getDescription());
        if (dto.getNodes() != null) {
            process.setNodes(processMapper.toNodeEntities(dto.getNodes()));
        }
        if (dto.getEdges() != null) {
            process.setEdges(processMapper.toEdgeEntities(dto.getEdges()));
        }
        if (dto.getViewport() != null) {
            process.setViewport(processMapper.toViewportEntity(dto.getViewport()));
        }

        processValidator.validateOrThrow(process);
        Process saved = processRepository.save(process);
        touchRecipe(recipeId);
        log.info("event=process_updated recipeId={} processId={} userId={} stepCount={} edgeCount={}",
                recipeId, saved.getId(), userId, sizeOf(saved.getNodes()), sizeOf(saved.getEdges()));
        return processMapper.toDTO(saved);
    }

    /**
     * Recipe-level save: replaces name/description/nodes/edges/viewport for every process named in
     * {@code items} (typically the recipe's MAIN process plus every SUBPROCESS the Recipe Tool's
     * unified editing session currently has loaded), after verifying the requesting user owns the
     * recipe. Every item is validated via {@code ProcessValidator} before any of them are persisted —
     * one invalid process rejects the whole batch — so the Recipe Tool can expose this as a single
     * "Save" action instead of one independent save per process.
     *
     * @param recipeId the id of the recipe every listed process is expected to belong to
     * @param userId   the id of the user requesting the update, used for ownership verification
     * <p>
     * Optimistic concurrency: when {@code baseRevision} is given, the save only goes through if the
     * recipe's process revision still equals it (otherwise {@code RecipeRevisionConflictException},
     * HTTP 409, and nothing is persisted); either way every successful save bumps the revision.</p>
     *
     * @param items        the replacement content for each process, keyed by its own {@code processId}
     * @param baseRevision the recipe process revision the client's snapshot was based on, or null to skip the check
     * @return the updated processes, in the same order as {@code items}, plus the recipe's new revision
     */
    public ProcessBatchUpdateResponseDTO updateAll(Long recipeId, Long userId, List<ProcessBatchItemDTO> items, Long baseRevision) {
        requireOwnedRecipe(recipeId, userId);

        List<Process> toSave = new ArrayList<>();
        for (ProcessBatchItemDTO item : items) {
            Process process = findProcessOrThrow(item.getProcessId());
            requireBelongsToRecipe(process, recipeId);

            process.setName(item.getName());
            process.setDescription(item.getDescription());
            if (item.getNodes() != null) {
                process.setNodes(processMapper.toNodeEntities(item.getNodes()));
            }
            if (item.getEdges() != null) {
                process.setEdges(processMapper.toEdgeEntities(item.getEdges()));
            }
            if (item.getViewport() != null) {
                process.setViewport(processMapper.toViewportEntity(item.getViewport()));
            }

            // Validates every item before any of them are saved below — a single invalid process
            // throws here and aborts the whole batch, leaving nothing partially persisted.
            processValidator.validateOrThrow(process);
            toSave.add(process);
        }

        // Claimed only once every item is known to be valid, so a rejected batch never moves the revision.
        Long revision = claimRevision(recipeId, baseRevision);
        List<Process> saved;
        try {
            saved = processRepository.saveAll(toSave);
        } catch (RuntimeException e) {
            // No Mongo transactions here: give the claimed revision back (if nobody claimed a newer
            // one meanwhile) so the client's retry from the same base isn't a conflict with itself.
            // Resending the full snapshot then repairs any process this partial saveAll did write.
            if (baseRevision != null) {
                recipeTemplateRepository.releaseProcessRevision(recipeId, revision);
            }
            throw e;
        }
        touchRecipe(recipeId);
        log.info("event=process_batch_updated recipeId={} userId={} processCount={} stepCount={} edgeCount={} revision={}",
                recipeId, userId, saved.size(),
                saved.stream().mapToInt(p -> sizeOf(p.getNodes())).sum(),
                saved.stream().mapToInt(p -> sizeOf(p.getEdges())).sum(),
                revision);
        return new ProcessBatchUpdateResponseDTO(
                revision,
                saved.stream().map(processMapper::toDTO).collect(Collectors.toList()));
    }

    /**
     * Atomically advances the recipe's process revision from {@code baseRevision} (see
     * {@code RecipeTemplateRepository.claimProcessRevision}), failing with a 409 if another save got
     * there first. A null base (older clients) bumps it unconditionally.
     *
     * @return the recipe's new revision
     */
    private Long claimRevision(Long recipeId, Long baseRevision) {
        if (baseRevision == null) {
            recipeTemplateRepository.incrementProcessRevision(recipeId);
            return recipeTemplateRepository.findById(recipeId)
                    .map(RecipeTemplate::getProcessRevision)
                    .orElse(null);
        }
        long claimed = baseRevision == 0L
                ? recipeTemplateRepository.claimInitialProcessRevision(recipeId)
                : recipeTemplateRepository.claimProcessRevision(recipeId, baseRevision);
        if (claimed == 0) {
            throw new RecipeRevisionConflictException(
                    "This recipe was changed elsewhere (another tab or device) since it was opened here");
        }
        return baseRevision + 1;
    }

    /**
     * Deletes a process, after verifying the requesting user owns the
     * recipe. Rejects deleting the recipe's MAIN process while it is still
     * referenced by {@code RecipeTemplate.mainProcessId}, and rejects
     * deleting any process still referenced by a PROCESS-kind node in
     * another process belonging to the same recipe.
     *
     * @param recipeId  the id of the recipe the process is expected to belong to
     * @param processId the id of the process to delete
     * @param userId    the id of the user requesting the deletion, used for ownership verification
     */
    public void delete(Long recipeId, Long processId, Long userId) {
        RecipeTemplate recipe = requireOwnedRecipe(recipeId, userId);
        Process process = findProcessOrThrow(processId);
        requireBelongsToRecipe(process, recipeId);

        if (recipe.getMainProcessId() != null && recipe.getMainProcessId().equals(processId)) {
            throw new ProcessValidationException(
                    "Cannot delete process " + processId + ": it is the recipe's MAIN process (referenced by mainProcessId)");
        }

        processRepository.deleteById(processId);
        touchRecipe(recipeId);
        log.info("event=process_deleted recipeId={} processId={} userId={} type={}",
                recipeId, processId, userId, process.getType());
    }

    /** The recipe changed when one of its processes did: keeps "recently updated" true to editing its steps. */
    private void touchRecipe(Long recipeId) {
        recipeTemplateRepository.touchUpdatedAt(recipeId, LocalDateTime.now());
    }

    /**
     * Copy-on-insert reuse (Phase 1 scope): deep-clones a SUBPROCESS
     * (recursively, including any nested subprocess references) into a new,
     * independent process within the same recipe. Does not create a live
     * link to the source process. The MAIN process cannot be copied.
     *
     * @param recipeId  the id of the recipe both the source and the copy belong to
     * @param processId the id of the SUBPROCESS to copy
     * @param userId    the id of the user requesting the copy, used for ownership verification
     * @return the newly created copy
     */
    public ProcessResponseDTO copy(Long recipeId, Long processId, Long userId) {
        requireOwnedRecipe(recipeId, userId);
        Process source = findProcessOrThrow(processId);

        if (!recipeId.equals(source.getRecipeId())) {
            throw new ProcessValidationException(
                    "Cannot copy process " + processId + ": it belongs to a different recipe");
        }
        if (source.getType() == ProcessType.MAIN) {
            throw new ProcessValidationException("The MAIN process cannot be copied");
        }

        Process copy = copyProcess(source);
        log.info("event=process_copied recipeId={} sourceProcessId={} processId={} userId={} stepCount={} edgeCount={}",
                recipeId, processId, copy.getId(), userId, sizeOf(copy.getNodes()), sizeOf(copy.getEdges()));
        return processMapper.toDTO(copy);
    }

    /**
     * Recipe copy: deep-clones every process of {@code sourceRecipeId} (MAIN and every SUBPROCESS)
     * into {@code targetRecipeId} as new, independent documents. Every copy gets a new process id
     * and new node/edge ids, and every STEP's Action On subprocess reference is rewritten to the
     * matching copy, so nothing in the target recipe points back at the source recipe (a reference
     * to a process outside the source recipe is dropped). Every copy is validated before any of
     * them are saved. Performs no ownership checks — callers (recipe copy) are responsible for
     * authorizing both recipes.
     *
     * @param sourceRecipeId the recipe whose processes are copied
     * @param targetRecipeId the (already persisted) recipe the copies belong to
     * @return the source-to-copy process id map, and the id of the copied MAIN process
     */
    public RecipeProcessCopyResult copyAllToRecipe(Long sourceRecipeId, Long targetRecipeId) {
        List<Process> sources = processRepository.findByRecipeId(sourceRecipeId);

        // Every id is allocated up front so a STEP's Action On reference can be rewritten to its
        // copy regardless of which order the processes are copied in.
        Map<Long, Long> processIdMap = new LinkedHashMap<>();
        // Node ids too: a main step's use of a Preparation step ({processId, stepId}) must follow that step's copy.
        Map<Long, Map<String, String>> nodeIdMaps = new LinkedHashMap<>();
        for (Process source : sources) {
            processIdMap.put(source.getId(), sequenceGeneratorService.generateSequence(Process.SEQUENCE_NAME));
            nodeIdMaps.put(source.getId(), ProcessGraphCloner.newNodeIds(source));
        }

        List<Process> copies = new ArrayList<>();
        Long copiedMainId = null;
        for (Process source : sources) {
            Process copy = cloneIntoRecipe(source, processIdMap.get(source.getId()), targetRecipeId, processIdMap, nodeIdMaps);
            // Validates every copy before any of them are saved below, so an invalid source
            // process aborts the whole copy without leaving a partial set of processes behind.
            processValidator.validateOrThrow(copy);
            copies.add(copy);
            if (copy.getType() == ProcessType.MAIN) {
                copiedMainId = copy.getId();
            }
        }

        processRepository.saveAll(copies);
        return new RecipeProcessCopyResult(processIdMap, copiedMainId, nodeIdMaps);
    }

    /**
     * Clones {@code original} into {@code targetRecipeId} under {@code newProcessId}, keeping its
     * type/name/description; see {@link ProcessGraphCloner#cloneGraph} for how the graph is copied.
     */
    private Process cloneIntoRecipe(
            Process original, Long newProcessId, Long targetRecipeId, Map<Long, Long> processIdMap, Map<Long, Map<String, String>> nodeIdMaps
    ) {
        Process copy = ProcessGraphCloner.cloneGraph(original, processIdMap, nodeIdMaps);
        copy.setId(newProcessId);
        copy.setType(original.getType());
        copy.setRecipeId(targetRecipeId);
        copy.setName(original.getName());
        copy.setDescription(original.getDescription());
        return copy;
    }

    /**
     * Clones {@code original} into a new, independently-owned {@link Process}
     * document in the same recipe, with freshly generated node/edge ids, so the
     * copy shares no live reference back to the source. A process is always a
     * single, self-contained STEP/CONDITION graph — it never embeds another
     * process as a node — so this is a flat clone, not a recursive one.
     *
     * @param original the process to copy
     * @return the saved copy
     */
    private Process copyProcess(Process original) {
        Process copy = ProcessGraphCloner.cloneGraph(original, null);
        copy.setId(sequenceGeneratorService.generateSequence(Process.SEQUENCE_NAME));
        copy.setType(original.getType());
        copy.setRecipeId(original.getRecipeId());
        copy.setName(original.getName() + COPY_SUFFIX);
        copy.setDescription(original.getDescription());

        processValidator.validateOrThrow(copy);
        Process saved = processRepository.save(copy);
        touchRecipe(original.getRecipeId());
        return saved;
    }

    private static int sizeOf(List<?> items) {
        return items == null ? 0 : items.size();
    }

    private Process findProcessOrThrow(Long processId) {
        return processRepository.findById(processId)
                .orElseThrow(() -> new NoSuchElementException("Process not found: " + processId));
    }

    private void requireBelongsToRecipe(Process process, Long recipeId) {
        if (!recipeId.equals(process.getRecipeId())) {
            throw new NoSuchElementException("Process " + process.getId() + " does not belong to recipe " + recipeId);
        }
    }

    private RecipeTemplate requireOwnedRecipe(Long recipeId, Long userId) {
        RecipeTemplate recipe = recipeTemplateRepository.findById(recipeId)
                .orElseThrow(() -> new NoSuchElementException("Recipe not found: " + recipeId));
        if (userId == null || !userId.equals(recipe.getCreatedBy())) {
            throw new RecipeAccessDeniedException("You do not have permission to modify this recipe");
        }
        return recipe;
    }

    private RecipeTemplate requireAccessibleRecipe(Long recipeId, Long requestingUserId) {
        RecipeTemplate recipe = recipeTemplateRepository.findById(recipeId)
                .orElseThrow(() -> new NoSuchElementException("Recipe not found: " + recipeId));
        if (recipe.getVisibility() == Visibility.PUBLIC) {
            return recipe;
        }
        if (requestingUserId != null && requestingUserId.equals(recipe.getCreatedBy())) {
            return recipe;
        }
        throw new RecipeAccessDeniedException("You do not have permission to view this recipe");
    }
}
