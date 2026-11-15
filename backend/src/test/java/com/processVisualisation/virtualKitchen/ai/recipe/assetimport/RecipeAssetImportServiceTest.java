package com.processVisualisation.virtualKitchen.ai.recipe.assetimport;

import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAsset;
import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAssetRepository;
import com.processVisualisation.virtualKitchen.ai.provider.ImageStorageClient;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiCommunicationException;
import com.processVisualisation.virtualKitchen.ai.recipe.assetimport.RecipeAssetImportDTO.AssetRef;
import com.processVisualisation.virtualKitchen.ai.recipe.assetimport.RecipeAssetImportDTO.Request;
import com.processVisualisation.virtualKitchen.ai.recipe.assetimport.RecipeAssetImportDTO.Response;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.NarrationProperties;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.NarrationScriptBuilder;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.StepNarrationCopier;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.StepNarrationCopier.CopyOutcome;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.StepNarrationStore;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.model.StepNarration;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationInput;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessNodeKind;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessType;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import com.processVisualisation.virtualKitchen.recipe.repository.ProcessRepository;
import com.processVisualisation.virtualKitchen.recipe.repository.RecipeTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A recipe created from a recipe file gets its own copies of the linked images and audio: new objects and new
 * records under the new recipe, read only from this application's storage, one entry failing alone.
 */
class RecipeAssetImportServiceTest {

    private static final long OWNER = 1L;
    private static final long RECIPE = 200L;
    private static final long MAIN = 10L;
    private static final long PREPARATION = 11L;
    private static final String SOURCE_IMAGE = "https://store.example/storage/v1/object/public/recipe-images/visualizations/46::a/5/x.png";
    private static final String SOURCE_AUDIO = "https://store.example/storage/v1/object/public/recipe-images/narrations/46/a/x.wav";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 4, 5};

    private RecipeTemplateRepository recipes;
    private ProcessRepository processes;
    private VisualizationAssetRepository assets;
    private RecipeProcessVisualizationService visualization;
    private ImageStorageClient imageStorage;
    private StepNarrationStore narrationStore;
    private StepNarrationCopier narrationCopier;
    private RecipeAssetImportService service;

    @BeforeEach
    void setUp() {
        recipes = mock(RecipeTemplateRepository.class);
        processes = mock(ProcessRepository.class);
        assets = mock(VisualizationAssetRepository.class);
        visualization = mock(RecipeProcessVisualizationService.class);
        imageStorage = mock(ImageStorageClient.class);
        narrationStore = mock(StepNarrationStore.class);
        narrationCopier = mock(StepNarrationCopier.class);

        RecipeTemplate recipe = new RecipeTemplate();
        recipe.setId(RECIPE);
        recipe.setCreatedBy(OWNER);
        when(recipes.findById(RECIPE)).thenReturn(Optional.of(recipe));
        when(processes.findById(MAIN)).thenReturn(Optional.of(process(MAIN, RECIPE, ProcessType.MAIN, "a", "b")));
        when(processes.findById(PREPARATION)).thenReturn(Optional.of(process(PREPARATION, RECIPE, ProcessType.SUBPROCESS, "p")));
        when(processes.setStepVisualization(anyLong(), anyString(), any(), any(), any())).thenReturn(1L);
        when(imageStorage.download(SOURCE_IMAGE)).thenReturn(new ImageStorageClient.StoredImage("image/png", PNG));
        when(imageStorage.upload(any(), anyString(), anyString())).thenAnswer(inv -> "https://store.example/" + inv.getArgument(2));
        when(visualization.savePrompt(anyString(), anyString())).thenAnswer(inv -> {
            VisualizationAsset asset = new VisualizationAsset();
            asset.setId(77L);
            asset.setVisualizationKey(inv.getArgument(0));
            asset.setImagePrompt(inv.getArgument(1));
            return asset;
        });

        service = new RecipeAssetImportService(recipes, processes, assets, visualization, imageStorage, narrationStore,
                narrationCopier, new NarrationScriptBuilder(new NarrationProperties()));
    }

    @Test
    void anImageIsStoredAgainUnderTheNewRecipe_andTheStepLinkedToTheCopy() {
        Response response = service.importAssets(OWNER, RECIPE, images(new AssetRef(MAIN, "a", SOURCE_IMAGE, "oil in a pan")));

        assertEquals(1, response.images().copied());
        assertTrue(response.images().failed().isEmpty());
        // A new object under the new recipe's own key and asset: never the source's file.
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(imageStorage).upload(bytes.capture(), eq("image/png"), path.capture());
        assertArrayEquals(PNG, bytes.getValue());
        assertTrue(path.getValue().startsWith("visualizations/200::a/77/"), path.getValue());
        assertTrue(path.getValue().endsWith(".png"), path.getValue());
        String copyUrl = "https://store.example/" + path.getValue();
        assertNotEquals(SOURCE_IMAGE, copyUrl);

        verify(visualization).savePrompt("200::a", "oil in a pan");
        ArgumentCaptor<VisualizationAsset> saved = ArgumentCaptor.forClass(VisualizationAsset.class);
        verify(assets).save(saved.capture());
        assertEquals(copyUrl, saved.getValue().getImageUrl());
        verify(processes).setStepVisualization(MAIN, "a", 77L, "oil in a pan", copyUrl);
    }

    @Test
    void aPreparationImageIsKeyedAsItsFlows_andTheFileTypeComesFromTheBytes() {
        // The storage says PNG; the bytes are a JPEG.
        when(imageStorage.download(SOURCE_IMAGE)).thenReturn(new ImageStorageClient.StoredImage("image/png", JPEG));

        Response response = service.importAssets(OWNER, RECIPE, images(new AssetRef(PREPARATION, "p", SOURCE_IMAGE, null)));

        assertEquals(1, response.images().copied());
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(imageStorage).upload(any(), eq("image/jpeg"), path.capture());
        assertTrue(path.getValue().startsWith("visualizations/200::flow::p/77/"), path.getValue());
        assertTrue(path.getValue().endsWith(".jpg"), path.getValue());
        verify(visualization).savePrompt("200::flow::p", RecipeAssetImportService.IMPORTED_PROMPT);
    }

    @Test
    void withTheFocusedView_aPreparationImageIsKeyedAsItsOwnStep_soTheNextVisualsJobKeepsIt() {
        when(visualization.focusedViewEnabled()).thenReturn(true);

        Response response = service.importAssets(OWNER, RECIPE, images(new AssetRef(PREPARATION, "p", SOURCE_IMAGE, null)));

        assertEquals(1, response.images().copied());
        verify(visualization).savePrompt("200::p", RecipeAssetImportService.IMPORTED_PROMPT);
    }

    @Test
    void eachBadImageFailsAlone_withAReason_andTheGoodOneIsStillCopied() {
        String elsewhere = "https://evil.example/internal/metadata";
        String missing = SOURCE_IMAGE + "?gone";
        String notAnImage = SOURCE_IMAGE + "?text";
        String huge = SOURCE_IMAGE + "?huge";
        // The storage client refuses anything outside its own bucket.
        when(imageStorage.download(elsewhere)).thenThrow(new AiCommunicationException("Not an image of this storage bucket"));
        when(imageStorage.download(missing)).thenThrow(new AiCommunicationException("404"));
        when(imageStorage.download(notAnImage)).thenReturn(new ImageStorageClient.StoredImage("image/png", "<html>".getBytes()));
        when(imageStorage.download(huge)).thenReturn(
                new ImageStorageClient.StoredImage("image/png", new byte[RecipeAssetImportService.MAX_IMAGE_BYTES + 1]));
        when(processes.findById(99L)).thenReturn(Optional.of(process(99L, 555L, ProcessType.MAIN, "a")));
        when(visualization.hasImage("200::b")).thenReturn(true);

        Response response = service.importAssets(OWNER, RECIPE, images(
                new AssetRef(MAIN, "a", elsewhere, null),
                new AssetRef(MAIN, "a", missing, null),
                new AssetRef(MAIN, "a", notAnImage, null),
                new AssetRef(MAIN, "a", huge, null),
                new AssetRef(MAIN, "nope", SOURCE_IMAGE, null),
                new AssetRef(99L, "a", SOURCE_IMAGE, null),
                new AssetRef(MAIN, "b", SOURCE_IMAGE, null),
                new AssetRef(MAIN, "a", " ", null),
                null,
                new AssetRef(MAIN, "a", SOURCE_IMAGE, null)));

        assertEquals(1, response.images().copied());
        assertEquals(List.of(
                "The original file could not be read",
                "The original file could not be read",
                "The original file is not a PNG, JPEG or WebP image",
                "The original image is too large",
                "The recipe has no such step",
                "The recipe has no such step",
                "The step already has an image",
                "No link to the original image",
                "Empty entry"), response.images().failed().stream().map(RecipeAssetImportDTO.Failure::reason).toList());
        // Only the one good image was written anywhere.
        verify(imageStorage, times(1)).upload(any(), anyString(), anyString());
        verify(processes, times(1)).setStepVisualization(anyLong(), anyString(), any(), any(), any());
        // Another recipe's process is never written to.
        verify(processes, never()).setStepVisualization(eq(99L), anyString(), any(), any(), any());
    }

    @Test
    void aStepRemovedMeanwhileIsReported() {
        when(processes.setStepVisualization(anyLong(), anyString(), any(), any(), any())).thenReturn(0L);

        Response response = service.importAssets(OWNER, RECIPE, images(new AssetRef(MAIN, "a", SOURCE_IMAGE, null)));

        assertEquals(0, response.images().copied());
        assertEquals("The step was removed while its image was being copied", response.images().failed().get(0).reason());
    }

    @Test
    void audioIsCopiedThroughItsNarrationRecord_withTheImportedStepsOwnTextFingerprint() {
        StepNarration source = new StepNarration();
        source.setAudioUrl(SOURCE_AUDIO);
        when(narrationStore.findReadyByAudioUrl(SOURCE_AUDIO)).thenReturn(Optional.of(source));
        Process main = process(MAIN, RECIPE, ProcessType.MAIN, "a", "b");
        when(visualization.prepareStepContexts(MAIN)).thenReturn(new RecipeProcessVisualizationService.ProcessStepPreparation(main, List.of(
                new RecipeProcessVisualizationService.StepContext(main.getNodes().get(0), input("Add the oil to the pan")),
                new RecipeProcessVisualizationService.StepContext(main.getNodes().get(1), silent()))));
        when(narrationCopier.copyTo(any(), anyLong(), anyLong(), anyString(), anyString(), anyBoolean())).thenReturn(CopyOutcome.COPIED);

        Response response = service.importAssets(OWNER, RECIPE, new Request(List.of(), List.of(
                new AssetRef(MAIN, "a", SOURCE_AUDIO, null),
                new AssetRef(MAIN, "b", SOURCE_AUDIO, null),
                new AssetRef(MAIN, "a", "https://evil.example/a.wav", null))));

        assertEquals(1, response.audio().copied());
        assertEquals(List.of("The step has nothing to narrate", "The original narration no longer exists"),
                response.audio().failed().stream().map(RecipeAssetImportDTO.Failure::reason).toList());
        String expectedHash = new NarrationScriptBuilder(new NarrationProperties()).build(input("Add the oil to the pan")).hash();
        // Never shared by URL: an imported recipe must own every file it plays.
        verify(narrationCopier, times(1)).copyTo(source, RECIPE, MAIN, "a", expectedHash, false);
        // The step texts are read once for the process, not once per entry.
        verify(visualization, times(1)).prepareStepContexts(MAIN);
    }

    @Test
    void audioThatCannotBeReadOrIsAlreadyThere_isReported() {
        when(narrationStore.findReadyByAudioUrl(anyString())).thenReturn(Optional.of(new StepNarration()));
        Process main = process(MAIN, RECIPE, ProcessType.MAIN, "a", "b");
        when(visualization.prepareStepContexts(MAIN)).thenReturn(new RecipeProcessVisualizationService.ProcessStepPreparation(main, List.of(
                new RecipeProcessVisualizationService.StepContext(main.getNodes().get(0), input("Add the oil")),
                new RecipeProcessVisualizationService.StepContext(main.getNodes().get(1), input("Add the onions")))));
        when(narrationCopier.copyTo(any(), anyLong(), anyLong(), eq("a"), anyString(), anyBoolean())).thenReturn(CopyOutcome.AUDIO_UNAVAILABLE);
        when(narrationCopier.copyTo(any(), anyLong(), anyLong(), eq("b"), anyString(), anyBoolean())).thenReturn(CopyOutcome.ALREADY_NARRATED);

        Response response = service.importAssets(OWNER, RECIPE, new Request(null, List.of(
                new AssetRef(MAIN, "a", SOURCE_AUDIO, null), new AssetRef(MAIN, "b", SOURCE_AUDIO, null))));

        assertEquals(0, response.audio().copied());
        assertEquals(List.of("The original audio file could not be read", "The step already has narration"),
                response.audio().failed().stream().map(RecipeAssetImportDTO.Failure::reason).toList());
        assertEquals(0, response.images().copied());
    }

    @Test
    void onlyTheOwnerMayImportIntoARecipe_andNothingIsReadForAnyoneElse() {
        assertThrows(RecipeAccessDeniedException.class,
                () -> service.importAssets(2L, RECIPE, images(new AssetRef(MAIN, "a", SOURCE_IMAGE, null))));
        assertThrows(RecipeAccessDeniedException.class, () -> service.importAssets(null, RECIPE, null));
        assertThrows(NoSuchElementException.class, () -> service.importAssets(OWNER, 404L, null));

        verifyNoInteractions(imageStorage, narrationStore, narrationCopier, processes);
    }

    @Test
    void anEmptyRequestCopiesNothing_andTooManyLinksAreRejected() {
        Response response = service.importAssets(OWNER, RECIPE, null);
        assertEquals(0, response.images().copied() + response.audio().copied());

        List<AssetRef> tooMany = Collections.nCopies(RecipeAssetImportService.MAX_ENTRIES + 1, new AssetRef(MAIN, "a", SOURCE_IMAGE, null));
        assertThrows(IllegalArgumentException.class, () -> service.importAssets(OWNER, RECIPE, new Request(tooMany, null)));
        verifyNoInteractions(imageStorage);
    }

    @Test
    void imageTypesAreRecognisedBySignature() {
        assertEquals(RecipeAssetImportService.ImageType.PNG, RecipeAssetImportService.ImageType.of(PNG));
        assertEquals(RecipeAssetImportService.ImageType.JPEG, RecipeAssetImportService.ImageType.of(JPEG));
        assertEquals(RecipeAssetImportService.ImageType.WEBP,
                RecipeAssetImportService.ImageType.of(new byte[]{'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P'}));
        assertEquals(null, RecipeAssetImportService.ImageType.of(new byte[]{'G', 'I', 'F', '8'}));
        assertEquals(null, RecipeAssetImportService.ImageType.of(new byte[0]));
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private static Request images(AssetRef... entries) {
        List<AssetRef> list = new ArrayList<>();
        Collections.addAll(list, entries);
        return new Request(list, null);
    }

    private static Process process(long id, long recipeId, ProcessType type, String... stepIds) {
        Process process = new Process();
        process.setId(id);
        process.setRecipeId(recipeId);
        process.setType(type);
        List<Process.ProcessNode> nodes = new ArrayList<>();
        for (String stepId : stepIds) {
            Process.ProcessNode node = new Process.ProcessNode();
            node.setId(stepId);
            node.setKind(ProcessNodeKind.STEP);
            nodes.add(node);
        }
        process.setNodes(nodes);
        return process;
    }

    /** A step with no action and no text: there is nothing to speak. */
    private static RecipeProcessVisualizationInput silent() {
        return new RecipeProcessVisualizationInput("", List.of(), List.of(), "", "", "", "", "", null);
    }

    private static RecipeProcessVisualizationInput input(String description) {
        return new RecipeProcessVisualizationInput("Add", List.of(), List.of(), description, "", "", "", "", null);
    }
}
