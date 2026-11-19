package com.processVisualisation.virtualKitchen.recipe.service;

import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAsset;
import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAssetRepository;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationStepImageLookup;
import com.processVisualisation.virtualKitchen.recipe.dto.RecipeThumbnailDTO;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessNodeKind;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessType;
import com.processVisualisation.virtualKitchen.recipe.repository.ProcessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecipeThumbnailServiceTest {

    private static final Long RECIPE_ID = 10L;
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 12, 0);

    private RecipeTemplateService recipeService;
    private ProcessRepository processRepository;
    private VisualizationAssetRepository assetRepository;
    private RecipeThumbnailService service;
    private final List<VisualizationAsset> assets = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        recipeService = mock(RecipeTemplateService.class);
        processRepository = mock(ProcessRepository.class);
        assetRepository = mock(VisualizationAssetRepository.class);
        service = new RecipeThumbnailService(recipeService, processRepository,
                new VisualizationStepImageLookup(assetRepository));
        when(assetRepository.findAllById(any())).thenAnswer(invocation -> {
            Collection<Long> ids = new ArrayList<>();
            ((Iterable<Long>) invocation.getArgument(0)).forEach(ids::add);
            return assets.stream().filter(asset -> ids.contains(asset.getId())).toList();
        });
    }

    @Test
    void recipeWithOneVisualUsesIt() {
        processes(main(RECIPE_ID, step("s1", 1L)));
        assets.add(asset(1L, "https://img/1.png", T0));

        RecipeThumbnailDTO thumbnail = service.resolve(RECIPE_ID, 5L);

        assertThat(thumbnail.getSource()).isEqualTo(RecipeThumbnailDTO.Source.GENERATED_VISUAL);
        assertThat(thumbnail.getThumbnailUrl()).isEqualTo("https://img/1.png");
        assertThat(thumbnail.getStepId()).isEqualTo("s1");
        assertThat(thumbnail.getVisualizationAssetId()).isEqualTo(1L);
        verify(recipeService).getRecipeDetails(RECIPE_ID, 5L);
    }

    @Test
    void lastStepInCookingOrderWinsEvenIfAnEarlierStepWasGeneratedLater() {
        // Array order is s3, s1, s2 but the edges say s1 -> s2 -> s3; s1's image is the newest.
        Process main = main(RECIPE_ID, step("s3", 3L), step("s1", 1L), step("s2", 2L));
        edges(main, "s1", "s2", "s2", "s3");
        processes(main);
        assets.add(asset(1L, "https://img/1.png", T0.plusHours(2)));
        assets.add(asset(2L, "https://img/2.png", T0.plusHours(1)));
        assets.add(asset(3L, "https://img/3.png", T0));

        RecipeThumbnailDTO thumbnail = service.resolve(RECIPE_ID, 5L);

        assertThat(thumbnail.getThumbnailUrl()).isEqualTo("https://img/3.png");
        assertThat(thumbnail.getStepId()).isEqualTo("s3");
    }

    @Test
    void lastStepWithoutImageFallsBackToThePreviousIllustratedStep() {
        Process main = main(RECIPE_ID, step("s1", 1L), step("s2", 2L), step("s3", null));
        edges(main, "s1", "s2", "s2", "s3");
        processes(main);
        assets.add(asset(1L, "https://img/1.png", T0));
        assets.add(asset(2L, "https://img/2.png", T0));

        assertThat(service.resolve(RECIPE_ID, 5L).getStepId()).isEqualTo("s2");
    }

    @Test
    void readsTheNestedVisualizationShapeTheRecipeToolSaves() {
        Process main = main(RECIPE_ID, nestedStep("s1", 30L, "https://img/30.png"), nestedStep("s2", 29L, "https://img/29.png"));
        edges(main, "s1", "s2");
        processes(main);
        assets.add(asset(29L, "https://img/29.png", T0));
        assets.add(asset(30L, "https://img/30.png", T0.plusMinutes(1)));

        RecipeThumbnailDTO thumbnail = service.resolve(RECIPE_ID, 5L);

        assertThat(thumbnail.getThumbnailUrl()).isEqualTo("https://img/29.png");
        assertThat(thumbnail.getVisualizationAssetId()).isEqualTo(29L);
    }

    @Test
    void stepWhoseAssetRecordIsGoneUsesTheImageOnTheNode() {
        processes(main(RECIPE_ID, nestedStep("s1", 77L, "https://img/node.png")));

        RecipeThumbnailDTO thumbnail = service.resolve(RECIPE_ID, 5L);

        assertThat(thumbnail.getThumbnailUrl()).isEqualTo("https://img/node.png");
        assertThat(thumbnail.getGeneratedAt()).isNull();
    }

    @Test
    void failedOrBlankAssetsAreSkippedEvenIfTheNodeStillHasAUrl() {
        Process main = main(RECIPE_ID,
                step("s1", 1L),
                step("s2", 2L),
                nestedStep("s3", 3L, "https://img/stale.png"),
                stepWithRawAssetId("s4", "not-a-number"));
        edges(main, "s1", "s2", "s2", "s3", "s3", "s4");
        processes(main);
        assets.add(asset(1L, "https://img/1.png", T0));
        assets.add(asset(2L, " ", T0));
        VisualizationAsset failed = asset(3L, null, T0);
        failed.setImageFailureReason("upload failed");
        assets.add(failed);

        assertThat(service.resolve(RECIPE_ID, 5L).getThumbnailUrl()).isEqualTo("https://img/1.png");
    }

    @Test
    void mainProcessIsPreferredOverSubprocesses() {
        processes(subprocess(RECIPE_ID, step("sub", 2L)), main(RECIPE_ID, step("m1", 1L)));
        assets.add(asset(1L, "https://img/main.png", T0));
        assets.add(asset(2L, "https://img/sub.png", T0.plusDays(1)));

        assertThat(service.resolve(RECIPE_ID, 5L).getThumbnailUrl()).isEqualTo("https://img/main.png");
    }

    @Test
    void withoutMainVisualsTheNewestSubprocessVisualIsUsed() {
        processes(
                main(RECIPE_ID, step("m1", null)),
                subprocess(RECIPE_ID, step("a", 1L)),
                subprocess(RECIPE_ID, step("b", 2L)));
        assets.add(asset(1L, "https://img/a.png", T0));
        assets.add(asset(2L, "https://img/b.png", T0.plusMinutes(5)));

        assertThat(service.resolve(RECIPE_ID, 5L).getThumbnailUrl()).isEqualTo("https://img/b.png");
    }

    @Test
    void recipeWithoutVisualsGetsTheDefaultIcon() {
        processes(main(RECIPE_ID, step("s1", null)));

        RecipeThumbnailDTO thumbnail = service.resolve(RECIPE_ID, 5L);

        assertThat(thumbnail.getSource()).isEqualTo(RecipeThumbnailDTO.Source.DEFAULT);
        assertThat(thumbnail.getThumbnailUrl()).isNull();
        assertThat(thumbnail.getFallbackIcon()).isEqualTo("🍲");
        verify(assetRepository, never()).findAllById(any());
    }

    @Test
    void recipeWithNoProcessesGetsTheDefaultIcon() {
        processes();

        assertThat(service.resolve(RECIPE_ID, null).getSource()).isEqualTo(RecipeThumbnailDTO.Source.DEFAULT);
    }

    @Test
    void conditionNodesAreIgnored() {
        Process.ProcessNode condition = step("c1", 1L);
        condition.setKind(ProcessNodeKind.CONDITION);
        processes(main(RECIPE_ID, condition));
        assets.add(asset(1L, "https://img/1.png", T0));

        assertThat(service.resolve(RECIPE_ID, 5L).getSource()).isEqualTo(RecipeThumbnailDTO.Source.DEFAULT);
    }

    @Test
    void missingRecipeIsNotFound() {
        when(recipeService.getRecipeDetails(99L, 5L)).thenThrow(new NoSuchElementException("Recipe not found: 99"));

        assertThatThrownBy(() -> service.resolve(99L, 5L)).isInstanceOf(NoSuchElementException.class);
        verify(processRepository, never()).findThumbnailFieldsByRecipeIdIn(anyCollection());
    }

    @Test
    void privateRecipeOfAnotherUserIsForbidden() {
        when(recipeService.getRecipeDetails(RECIPE_ID, 6L)).thenThrow(new RecipeAccessDeniedException("private"));

        assertThatThrownBy(() -> service.resolve(RECIPE_ID, 6L)).isInstanceOf(RecipeAccessDeniedException.class);
    }

    @Test
    void resolveAllResolvesEachRecipeInOneBatch() {
        processes(main(RECIPE_ID, step("s1", 1L)), main(11L, step("s9", 9L)));
        assets.add(asset(1L, "https://img/1.png", T0));

        Map<Long, RecipeThumbnailDTO> thumbnails = service.resolveAll(List.of(RECIPE_ID, 11L, 12L));

        assertThat(thumbnails).containsOnlyKeys(RECIPE_ID, 11L, 12L);
        assertThat(thumbnails.get(RECIPE_ID).getThumbnailUrl()).isEqualTo("https://img/1.png");
        assertThat(thumbnails.get(11L).getSource()).isEqualTo(RecipeThumbnailDTO.Source.DEFAULT);
        assertThat(thumbnails.get(12L).getSource()).isEqualTo(RecipeThumbnailDTO.Source.DEFAULT);
    }

    private void processes(Process... processes) {
        when(processRepository.findThumbnailFieldsByRecipeIdIn(anyCollection())).thenReturn(List.of(processes));
    }

    private static Process main(Long recipeId, Process.ProcessNode... nodes) {
        return process(recipeId, ProcessType.MAIN, nodes);
    }

    private static Process subprocess(Long recipeId, Process.ProcessNode... nodes) {
        return process(recipeId, ProcessType.SUBPROCESS, nodes);
    }

    private static Process process(Long recipeId, ProcessType type, Process.ProcessNode... nodes) {
        Process process = new Process();
        process.setRecipeId(recipeId);
        process.setType(type);
        process.setNodes(new ArrayList<>(List.of(nodes)));
        process.setEdges(new ArrayList<>());
        return process;
    }

    /** Adds edges given as source/target pairs. */
    private static void edges(Process process, String... sourceTargetPairs) {
        for (int i = 0; i < sourceTargetPairs.length; i += 2) {
            Process.ProcessEdge edge = new Process.ProcessEdge();
            edge.setId("e" + i);
            edge.setSource(sourceTargetPairs[i]);
            edge.setTarget(sourceTargetPairs[i + 1]);
            process.getEdges().add(edge);
        }
    }

    private static Process.ProcessNode step(String id, Long assetId) {
        return stepWithRawAssetId(id, assetId);
    }

    private static Process.ProcessNode stepWithRawAssetId(String id, Object assetId) {
        Process.ProcessNode node = new Process.ProcessNode();
        node.setId(id);
        node.setKind(ProcessNodeKind.STEP);
        Map<String, Object> data = new LinkedHashMap<>();
        if (assetId != null) {
            data.put("visualizationAssetId", assetId);
        }
        node.setData(data);
        return node;
    }

    /** A step as the Recipe Tool saves it: {@code visualization: {assetId, imageUrl, status}}, no flat fields. */
    private static Process.ProcessNode nestedStep(String id, Long assetId, String imageUrl) {
        Process.ProcessNode node = stepWithRawAssetId(id, null);
        Map<String, Object> visualization = new LinkedHashMap<>();
        visualization.put("assetId", assetId);
        visualization.put("imageUrl", imageUrl);
        visualization.put("status", "generated");
        node.getData().put("visualization", visualization);
        return node;
    }

    private static VisualizationAsset asset(Long id, String imageUrl, LocalDateTime updatedAt) {
        VisualizationAsset asset = new VisualizationAsset();
        asset.setId(id);
        asset.setVisualizationKey(RECIPE_ID + "::s" + id);
        asset.setImageUrl(imageUrl);
        asset.setUpdatedAt(updatedAt);
        return asset;
    }
}
