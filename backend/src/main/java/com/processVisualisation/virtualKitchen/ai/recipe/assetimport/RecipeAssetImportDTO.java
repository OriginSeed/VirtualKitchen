package com.processVisualisation.virtualKitchen.ai.recipe.assetimport;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import com.processVisualisation.virtualKitchen.common.web.InputLimits;
import java.util.List;

/** Request and response of {@code POST /api/v1/recipes/{recipeId}/assets/import}. */
public final class RecipeAssetImportDTO {

    private RecipeAssetImportDTO() {
    }

    /**
     * One file a recipe file links to, for one step of the new recipe.
     *
     * @param processId the new recipe's process holding the step
     * @param stepId the step (node) id, the same as in the file
     * @param url where the original is: an image of this application's storage, or a narration's audio URL
     * @param prompt the image's prompt, kept with the copy; unused for audio
     */
    public record AssetRef(Long processId, @Size(max = InputLimits.ID) String stepId,
                           @Size(max = InputLimits.URL) String url,
                           @Size(max = InputLimits.IMAGE_PROMPT, message = "Image prompt can be at most " + InputLimits.IMAGE_PROMPT + " characters") String prompt) {
    }

    public record Request(@Valid List<AssetRef> images, @Valid List<AssetRef> audio) {
    }

    /** An entry that was not copied, and why (a sentence the user can be shown). */
    public record Failure(Long processId, String stepId, String reason) {
    }

    public record Outcome(int copied, List<Failure> failed) {
    }

    public record Response(Outcome images, Outcome audio) {
    }
}
