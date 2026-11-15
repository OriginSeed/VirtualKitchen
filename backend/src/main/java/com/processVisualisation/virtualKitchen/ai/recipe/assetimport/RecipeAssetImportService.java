package com.processVisualisation.virtualKitchen.ai.recipe.assetimport;

import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAsset;
import com.processVisualisation.virtualKitchen.ai.asset.VisualizationAssetRepository;
import com.processVisualisation.virtualKitchen.ai.provider.ImageStorageClient;
import com.processVisualisation.virtualKitchen.ai.recipe.assetimport.RecipeAssetImportDTO.AssetRef;
import com.processVisualisation.virtualKitchen.ai.recipe.assetimport.RecipeAssetImportDTO.Failure;
import com.processVisualisation.virtualKitchen.ai.recipe.assetimport.RecipeAssetImportDTO.Outcome;
import com.processVisualisation.virtualKitchen.ai.recipe.assetimport.RecipeAssetImportDTO.Request;
import com.processVisualisation.virtualKitchen.ai.recipe.assetimport.RecipeAssetImportDTO.Response;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.NarrationScriptBuilder;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.StepNarrationCopier;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.StepNarrationStore;
import com.processVisualisation.virtualKitchen.ai.recipe.narration.model.StepNarration;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.RecipeProcessVisualizationService;
import com.processVisualisation.virtualKitchen.ai.recipe.visualization.VisualizationKeyBuilder;
import com.processVisualisation.virtualKitchen.recipe.exception.RecipeAccessDeniedException;
import com.processVisualisation.virtualKitchen.recipe.model.Process;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessNodeKind;
import com.processVisualisation.virtualKitchen.recipe.model.ProcessType;
import com.processVisualisation.virtualKitchen.recipe.model.RecipeTemplate;
import com.processVisualisation.virtualKitchen.recipe.repository.ProcessRepository;
import com.processVisualisation.virtualKitchen.recipe.repository.RecipeTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Gives a recipe built from a recipe file its own copies of the images and narration audio the file links to:
 * each linked file is read from this application's own storage and stored again as a new object, with a new
 * record keyed to the new recipe's step. The new recipe never points at the source recipe's files, so nothing
 * the source's owner does later (regenerating, deleting) can change it.
 * <p>
 * Only this application's storage is ever read: an image by a URL of its own bucket
 * ({@link ImageStorageClient#download}), audio through the narration record that holds that URL. A link to
 * anywhere else fails that entry. Writes go only into a recipe the caller owns.
 * <p>
 * Best effort per entry: a link whose original is gone, an unreadable or unsuitable file, or a step the recipe
 * doesn't have fails that entry alone, and is reported with its reason.
 */
@Service
public class RecipeAssetImportService {

    private static final Logger log = LoggerFactory.getLogger(RecipeAssetImportService.class);

    /** Most links of each kind one request may carry; a recipe has far fewer steps. */
    static final int MAX_ENTRIES = 300;
    /** Largest image copied. Generated step images are well under 2 MB. */
    static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    /** The prompt recorded for an image whose file names none. */
    static final String IMPORTED_PROMPT = "Imported image";

    private final RecipeTemplateRepository recipeTemplateRepository;
    private final ProcessRepository processRepository;
    private final VisualizationAssetRepository assetRepository;
    private final RecipeProcessVisualizationService visualizationService;
    private final ImageStorageClient imageStorageClient;
    private final StepNarrationStore narrationStore;
    private final StepNarrationCopier narrationCopier;
    private final NarrationScriptBuilder scriptBuilder;

    public RecipeAssetImportService(
            RecipeTemplateRepository recipeTemplateRepository,
            ProcessRepository processRepository,
            VisualizationAssetRepository assetRepository,
            RecipeProcessVisualizationService visualizationService,
            ImageStorageClient imageStorageClient,
            StepNarrationStore narrationStore,
            StepNarrationCopier narrationCopier,
            NarrationScriptBuilder scriptBuilder
    ) {
        this.recipeTemplateRepository = recipeTemplateRepository;
        this.processRepository = processRepository;
        this.assetRepository = assetRepository;
        this.visualizationService = visualizationService;
        this.imageStorageClient = imageStorageClient;
        this.narrationStore = narrationStore;
        this.narrationCopier = narrationCopier;
        this.scriptBuilder = scriptBuilder;
    }

    /**
     * Copies every linked image and audio file into the recipe.
     *
     * @throws NoSuchElementException      when the recipe does not exist
     * @throws RecipeAccessDeniedException when the caller does not own it
     * @throws IllegalArgumentException    when the request carries more links than a recipe can have
     */
    public Response importAssets(Long userId, Long recipeId, Request request) {
        RecipeTemplate recipe = recipeTemplateRepository.findById(recipeId)
                .orElseThrow(() -> new NoSuchElementException("Recipe not found: " + recipeId));
        if (userId == null || !userId.equals(recipe.getCreatedBy())) {
            throw new RecipeAccessDeniedException("You do not have permission to modify this recipe");
        }
        List<AssetRef> images = request == null || request.images() == null ? List.of() : request.images();
        List<AssetRef> audio = request == null || request.audio() == null ? List.of() : request.audio();
        if (images.size() > MAX_ENTRIES || audio.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many assets in one import (at most " + MAX_ENTRIES + " of each kind)");
        }

        // Each process is loaded once, however many of its steps the file links.
        Map<Long, Optional<Process>> processes = new HashMap<>();
        Outcome imageOutcome = copyAll(images, entry -> copyImage(recipeId, entry, processes));
        Map<Long, Map<String, String>> hashes = new HashMap<>();
        Outcome audioOutcome = copyAll(audio, entry -> copyAudio(recipeId, entry, processes, hashes));
        log.info("event=recipe_assets_imported recipeId={} imagesCopied={} imagesFailed={} audioCopied={} audioFailed={}",
                recipeId, imageOutcome.copied(), imageOutcome.failed().size(), audioOutcome.copied(), audioOutcome.failed().size());
        return new Response(imageOutcome, audioOutcome);
    }

    /** Copies one entry; returns null on success, else why it was not copied. */
    private interface EntryCopy {
        String copy(AssetRef entry);
    }

    private Outcome copyAll(List<AssetRef> entries, EntryCopy copy) {
        int copied = 0;
        List<Failure> failed = new ArrayList<>();
        for (AssetRef entry : entries) {
            String reason;
            try {
                reason = entry == null ? "Empty entry" : copy.copy(entry);
            } catch (RuntimeException e) {
                // Storage and database errors are that entry's failure, never the whole import's.
                log.warn("event=recipe_asset_import_failed processId={} stepId={} errorType={} error=\"{}\"",
                        entry == null ? null : entry.processId(), entry == null ? null : entry.stepId(),
                        e.getClass().getSimpleName(), e.getMessage());
                reason = "The original file could not be read";
            }
            if (reason == null) {
                copied++;
            } else {
                failed.add(new Failure(entry == null ? null : entry.processId(), entry == null ? null : entry.stepId(), reason));
            }
        }
        return new Outcome(copied, failed);
    }

    private String copyImage(Long recipeId, AssetRef entry, Map<Long, Optional<Process>> processes) {
        Optional<Process> process = stepProcess(recipeId, entry, processes);
        if (process.isEmpty()) {
            return "The recipe has no such step";
        }
        if (!StringUtils.hasText(entry.url())) {
            return "No link to the original image";
        }
        String recipeKey = String.valueOf(recipeId);
        // With the focused view, every Preparation step has its own image, keyed like any step. Without it, a
        // Preparation's image belongs to its flow and is keyed by the step that makes the flow's output.
        String key = process.get().getType() == ProcessType.SUBPROCESS && !visualizationService.focusedViewEnabled()
                ? VisualizationKeyBuilder.buildFlow(recipeKey, entry.stepId())
                : VisualizationKeyBuilder.build(recipeKey, entry.stepId());
        if (visualizationService.hasImage(key)) {
            return "The step already has an image";
        }

        // Only a URL of this application's own bucket is read; anything else is refused by the storage client.
        ImageStorageClient.StoredImage original = imageStorageClient.download(entry.url().trim());
        byte[] data = original == null ? null : original.data();
        if (data == null || data.length == 0) {
            return "The original image is empty";
        }
        if (data.length > MAX_IMAGE_BYTES) {
            return "The original image is too large";
        }
        ImageType type = ImageType.of(data);
        if (type == null) {
            return "The original file is not a PNG, JPEG or WebP image";
        }

        String prompt = StringUtils.hasText(entry.prompt()) ? entry.prompt().trim() : IMPORTED_PROMPT;
        VisualizationAsset asset = visualizationService.savePrompt(key, prompt);
        String url = imageStorageClient.upload(data, type.mimeType,
                "visualizations/%s/%s/%s.%s".formatted(key, asset.getId(), UUID.randomUUID(), type.extension));
        asset.setImageUrl(url);
        asset.setImageFailureReason(null);
        assetRepository.save(asset);
        if (processRepository.setStepVisualization(entry.processId(), entry.stepId(), asset.getId(), asset.getImagePrompt(), url) == 0) {
            return "The step was removed while its image was being copied";
        }
        return null;
    }

    private String copyAudio(Long recipeId, AssetRef entry, Map<Long, Optional<Process>> processes,
                             Map<Long, Map<String, String>> hashes) {
        if (stepProcess(recipeId, entry, processes).isEmpty()) {
            return "The recipe has no such step";
        }
        if (!StringUtils.hasText(entry.url())) {
            return "No link to the original audio";
        }
        // The audio is found through its narration record, never fetched from the address itself.
        Optional<StepNarration> source = narrationStore.findReadyByAudioUrl(entry.url().trim());
        if (source.isEmpty()) {
            return "The original narration no longer exists";
        }
        // The copy carries the fingerprint of what the imported step says, so it plays only if that still matches.
        String hash = hashes.computeIfAbsent(entry.processId(), this::scriptHashes).get(entry.stepId());
        if (!StringUtils.hasText(hash)) {
            return "The step has nothing to narrate";
        }
        return switch (narrationCopier.copyTo(source.get(), recipeId, entry.processId(), entry.stepId(), hash, false)) {
            case COPIED -> null;
            case AUDIO_UNAVAILABLE -> "The original audio file could not be read";
            case ALREADY_NARRATED -> "The step already has narration";
        };
    }

    /** The process holding the entry's step, when it is a STEP of a process of this recipe. */
    private Optional<Process> stepProcess(Long recipeId, AssetRef entry, Map<Long, Optional<Process>> processes) {
        if (entry.processId() == null || !StringUtils.hasText(entry.stepId())) {
            return Optional.empty();
        }
        return processes.computeIfAbsent(entry.processId(), id -> processRepository.findById(id)
                        .filter(process -> recipeId.equals(process.getRecipeId())))
                .filter(process -> process.getNodes() != null && process.getNodes().stream()
                        .anyMatch(node -> node != null && entry.stepId().equals(node.getId()) && node.getKind() == ProcessNodeKind.STEP));
    }

    /** The spoken-text fingerprint of every step of a process, by step id. */
    private Map<String, String> scriptHashes(Long processId) {
        Map<String, String> hashes = new HashMap<>();
        for (RecipeProcessVisualizationService.StepContext step : visualizationService.prepareStepContexts(processId).steps()) {
            hashes.put(step.node().getId(), scriptBuilder.build(step.input()).hash());
        }
        return hashes;
    }

    /** The image formats step images are stored in, recognised by file signature (never by a claimed type). */
    enum ImageType {
        PNG("image/png", "png"),
        JPEG("image/jpeg", "jpg"),
        WEBP("image/webp", "webp");

        final String mimeType;
        final String extension;

        ImageType(String mimeType, String extension) {
            this.mimeType = mimeType;
            this.extension = extension;
        }

        static ImageType of(byte[] data) {
            if (startsWith(data, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
                return PNG;
            }
            if (startsWith(data, 0, 0xFF, 0xD8, 0xFF)) {
                return JPEG;
            }
            if (startsWith(data, 0, 'R', 'I', 'F', 'F') && startsWith(data, 8, 'W', 'E', 'B', 'P')) {
                return WEBP;
            }
            return null;
        }

        private static boolean startsWith(byte[] data, int offset, int... signature) {
            if (data.length < offset + signature.length) {
                return false;
            }
            for (int i = 0; i < signature.length; i++) {
                if ((data[offset + i] & 0xFF) != signature[i]) {
                    return false;
                }
            }
            return true;
        }
    }
}
