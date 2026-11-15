package com.processVisualisation.virtualKitchen.ai.recipe.assetimport;

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
    public record AssetRef(Long processId, String stepId, String url, String prompt) {
    }

    public record Request(List<AssetRef> images, List<AssetRef> audio) {
    }

    /** An entry that was not copied, and why (a sentence the user can be shown). */
    public record Failure(Long processId, String stepId, String reason) {
    }

    public record Outcome(int copied, List<Failure> failed) {
    }

    public record Response(Outcome images, Outcome audio) {
    }
}
