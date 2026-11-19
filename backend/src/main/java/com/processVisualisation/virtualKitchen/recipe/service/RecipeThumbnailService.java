package com.processVisualisation.virtualKitchen.recipe.service;

import com.processVisualisation.virtualKitchen.recipe.dto.RecipeThumbnailDTO;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessType;
import com.processVisualisation.virtualKitchen.recipe.repository.ProcessRepository;
import com.processVisualisation.virtualKitchen.recipe.service.StepImageLookup.StepImage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Resolves the image that represents a recipe, so clients never have to pick among step visuals
 * themselves: the visual of the <b>last illustrated step</b> of the recipe's MAIN process (in
 * cooking order, i.e. the process graph's topological order — usually the finished dish), or the
 * default recipe icon when there is none.
 * <p>
 * Cooking order is used rather than generation time because one visualization job generates all
 * of a process's steps in parallel, so their timestamps say nothing about which is last. If the
 * MAIN process has no visual, the most recently generated last-illustrated step among the
 * subprocesses is used. This only reads existing visuals; it never generates an image.
 * <p>
 * A step's visual is read from either shape the node data takes: the flat
 * {@code visualizationAssetId}/{@code imageUrl} written by the visualization job, or the nested
 * {@code visualization: {assetId, imageUrl}} the Recipe Tool rewrites it to when it saves.
 */
@Service
public class RecipeThumbnailService {

    /** The default recipe icon (🍲), the same fallback the frontend's recipe hero already shows. */
    public static final String DEFAULT_RECIPE_ICON = "🍲";

    private static final Comparator<Visual> LATEST_FIRST = Comparator
            .comparing(Visual::generatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Visual::assetId, Comparator.nullsLast(Comparator.reverseOrder()));

    private final RecipeTemplateService recipeService;
    private final ProcessRepository processRepository;
    private final StepImageLookup stepImageLookup;

    public RecipeThumbnailService(
            RecipeTemplateService recipeService,
            ProcessRepository processRepository,
            StepImageLookup stepImageLookup) {
        this.recipeService = recipeService;
        this.processRepository = processRepository;
        this.stepImageLookup = stepImageLookup;
    }

    /**
     * Resolves one recipe's thumbnail, following the recipe's visibility (owner, or public).
     *
     * @throws NoSuchElementException when the recipe doesn't exist
     * @throws com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException
     *         when the viewer may not read it
     */
    public RecipeThumbnailDTO resolve(Long recipeId, Long viewerId) {
        recipeService.getRecipeDetails(recipeId, viewerId);
        return resolveAll(List.of(recipeId)).get(recipeId);
    }

    /**
     * Resolves thumbnails for many recipes in two queries (their processes, then the referenced
     * assets). No access check: callers pass recipes they have already listed for the viewer.
     *
     * @return a thumbnail for every given recipe id, in the given order
     */
    public Map<Long, RecipeThumbnailDTO> resolveAll(Collection<Long> recipeIds) {
        Map<Long, RecipeThumbnailDTO> thumbnails = new LinkedHashMap<>();
        if (recipeIds == null || recipeIds.isEmpty()) {
            return thumbnails;
        }

        List<Process> processes = processRepository.findThumbnailFieldsByRecipeIdIn(recipeIds).stream()
                .filter(process -> process.getRecipeId() != null)
                .toList();
        Map<Long, StepImage> assetsById = loadAssets(processes);
        Map<Long, List<Process>> processesByRecipe = processes.stream()
                .collect(Collectors.groupingBy(Process::getRecipeId));

        for (Long recipeId : recipeIds) {
            thumbnails.put(recipeId, pickThumbnail(processesByRecipe.getOrDefault(recipeId, List.of()), assetsById)
                    .map(visual -> generated(recipeId, visual))
                    .orElseGet(() -> defaultThumbnail(recipeId)));
        }
        return thumbnails;
    }

    private Map<Long, StepImage> loadAssets(List<Process> processes) {
        List<Long> assetIds = processes.stream()
                .filter(process -> process.getNodes() != null)
                .flatMap(process -> process.getNodes().stream())
                .filter(Objects::nonNull)
                .map(node -> assetId(node.getData()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        return stepImageLookup.findByIds(assetIds);
    }

    /** The MAIN process's last illustrated step; failing that, the newest of the subprocesses' last illustrated steps. */
    private static Optional<Visual> pickThumbnail(List<Process> processes, Map<Long, StepImage> assetsById) {
        Optional<Visual> main = processes.stream()
                .filter(process -> process.getType() == ProcessType.MAIN)
                .map(process -> lastIllustratedStep(process, assetsById))
                .flatMap(Optional::stream)
                .findFirst();
        if (main.isPresent()) {
            return main;
        }
        return processes.stream()
                .filter(process -> process.getType() != ProcessType.MAIN)
                .map(process -> lastIllustratedStep(process, assetsById))
                .flatMap(Optional::stream)
                .min(LATEST_FIRST);
    }

    private static Optional<Visual> lastIllustratedStep(Process process, Map<Long, StepImage> assetsById) {
        List<Process.ProcessNode> steps = ProcessStepOrder.orderedSteps(process);
        for (int i = steps.size() - 1; i >= 0; i--) {
            Optional<Visual> visual = visualOf(steps.get(i), assetsById);
            if (visual.isPresent()) {
                return visual;
            }
        }
        return Optional.empty();
    }

    /**
     * The step's usable image. When the step references an asset that still exists, the asset is
     * the source of truth (its URL, or nothing if its generation failed); a step whose asset record
     * is gone or was never recorded falls back to the image URL stored on the node itself.
     */
    private static Optional<Visual> visualOf(Process.ProcessNode node, Map<Long, StepImage> assetsById) {
        Map<String, Object> data = node.getData();
        if (data == null) {
            return Optional.empty();
        }
        Long assetId = assetId(data);
        StepImage image = assetId == null ? null : assetsById.get(assetId);
        if (image != null) {
            boolean usable = StringUtils.hasText(image.imageUrl()) && !image.failed();
            return usable
                    ? Optional.of(new Visual(node.getId(), image.assetId(), image.imageUrl(), image.generatedAt()))
                    : Optional.empty();
        }
        String nodeUrl = imageUrl(data);
        return StringUtils.hasText(nodeUrl) ? Optional.of(new Visual(node.getId(), assetId, nodeUrl, null)) : Optional.empty();
    }

    private static Long assetId(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        Long flat = parseId(data.get("visualizationAssetId"));
        return flat != null ? flat : parseId(nested(data).get("assetId"));
    }

    private static String imageUrl(Map<String, Object> data) {
        Object flat = data.get("imageUrl");
        if (flat instanceof String url && StringUtils.hasText(url)) {
            return url;
        }
        Object nested = nested(data).get("imageUrl");
        return nested instanceof String url ? url : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> data) {
        Object visualization = data.get("visualization");
        return visualization instanceof Map ? (Map<String, Object>) visualization : Map.of();
    }

    /** Node data is an opaque map written by the editor too, so the id may be a number, a numeric string, or junk. */
    private static Long parseId(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static RecipeThumbnailDTO generated(Long recipeId, Visual visual) {
        return RecipeThumbnailDTO.builder()
                .recipeId(recipeId)
                .thumbnailUrl(visual.imageUrl())
                .source(RecipeThumbnailDTO.Source.GENERATED_VISUAL)
                .fallbackIcon(DEFAULT_RECIPE_ICON)
                .visualizationAssetId(visual.assetId())
                .stepId(visual.stepId())
                .generatedAt(visual.generatedAt())
                .build();
    }

    private static RecipeThumbnailDTO defaultThumbnail(Long recipeId) {
        return RecipeThumbnailDTO.builder()
                .recipeId(recipeId)
                .source(RecipeThumbnailDTO.Source.DEFAULT)
                .fallbackIcon(DEFAULT_RECIPE_ICON)
                .build();
    }

    private record Visual(String stepId, Long assetId, String imageUrl, LocalDateTime generatedAt) {
    }
}
