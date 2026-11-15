package com.processVisualisation.virtualKitchen.ai.provider;

import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Common contract implemented by every image-generation AI provider client
 * (Draw Things, Gemini, DeepInfra) used by the Virtual Kitchen application to turn a
 * text prompt into a generated image.
 */
public interface ImageGenerationClient {

    /**
     * Holds the bytes and MIME type of an image produced by an image
     * generation provider.
     *
     * @param mimeType the MIME type of the generated image (e.g. "image/png")
     * @param data the raw image bytes
     * @param usage the tokens the provider reported for this call ({@link ProviderUsage#NONE} if none)
     */
    public record GeneratedImage(
            String mimeType,
            byte[] data,
            ProviderUsage usage
    ) {
        /** An image whose provider reports no token usage (or one restored from storage, where usage is moot). */
        public GeneratedImage(String mimeType, byte[] data) {
            this(mimeType, data, ProviderUsage.NONE);
        }
    }

    /**
     * Generates an image from the given text prompt using the underlying
     * provider.
     *
     * @param prompt the text prompt describing the image to generate
     * @return the generated image data and its MIME type
     * @throws JsonProcessingException if the provider's response cannot be parsed as JSON
     */
    GeneratedImage generate(String prompt) throws JsonProcessingException;

    /**
     * An image sent along with the prompt for the model to follow (e.g. a recipe's empty scene, so every step is
     * drawn in the same kitchen with the same cookware).
     *
     * @param mimeType the MIME type of the image (e.g. "image/png")
     * @param data the raw image bytes
     */
    record ReferenceImage(String mimeType, byte[] data) {
    }

    /**
     * Generates an image from the prompt, following {@code references} where the provider can take image input.
     * Providers that can't (the default) ignore them and generate from the prompt alone.
     *
     * @param prompt the text prompt describing the image to generate
     * @param references images the result should follow; may be empty
     * @return the generated image data and its MIME type
     * @throws JsonProcessingException if the provider's response cannot be parsed as JSON
     */
    default GeneratedImage generate(String prompt, java.util.List<ReferenceImage> references) throws JsonProcessingException {
        return generate(prompt);
    }

    /**
     * Per-request generation settings (e.g. chosen by an image's purpose). A null field means "use the provider's
     * configured default"; each provider sends only the settings its model supports and ignores the rest.
     *
     * @param width output width in pixels, or null
     * @param height output height in pixels, or null
     * @param steps inference steps, or null; only providers whose API takes a step count send it
     * @param seed a fixed generation seed, or null; only providers whose API takes one send it. A seed alone does not
     *             make two different prompts look alike: it is a small aid next to a shared scene and references.
     */
    record ImageGenerationSettings(Integer width, Integer height, Integer steps, String modelId, Long seed) {

        /** No overrides: every provider uses its own configured settings, exactly as before settings existed. */
        public static final ImageGenerationSettings NONE = new ImageGenerationSettings(null, null, null);

        public ImageGenerationSettings(Integer width, Integer height, Integer steps) {
            this(width, height, steps, null, null);
        }

        public ImageGenerationSettings(Integer width, Integer height, Integer steps, String modelId) {
            this(width, height, steps, modelId, null);
        }

        /**
         * The same settings for the selected registry model's provider model id, so the model that runs is the one
         * that was selected (and charged for), not only the provider's configured one.
         */
        public ImageGenerationSettings withModel(String providerModelId) {
            return new ImageGenerationSettings(width, height, steps, providerModelId, seed);
        }

        /** The same settings with a fixed seed, for providers that take one. */
        public ImageGenerationSettings withSeed(Long seed) {
            return new ImageGenerationSettings(width, height, steps, modelId, seed);
        }

        /**
         * No size, steps or seed requested. The model id doesn't count: without a size the call stays a plain
         * {@code generate(prompt)}, and the provider runs its configured model.
         */
        public boolean isNone() {
            return width == null && height == null && steps == null && seed == null;
        }
    }

    /**
     * Generates an image with per-request {@code settings}. Providers that support none of them (the default)
     * ignore the settings and behave like {@link #generate(String, java.util.List)}.
     *
     * @param prompt the text prompt describing the image to generate
     * @param references images the result should follow; may be empty
     * @param settings the settings to apply where the provider supports them; {@link ImageGenerationSettings#NONE} for none
     * @return the generated image data and its MIME type
     * @throws JsonProcessingException if the provider's response cannot be parsed as JSON
     */
    default GeneratedImage generate(String prompt, java.util.List<ReferenceImage> references,
                                    ImageGenerationSettings settings) throws JsonProcessingException {
        return generate(prompt, references);
    }

    /**
     * Whether {@link #generate(String, java.util.List)} actually sends the reference images. Callers only phrase a
     * prompt around "the reference image" when this is true.
     */
    default boolean supportsReferenceImages() {
        return false;
    }

    /**
     * How many reference images one request can carry, for providers that {@link #supportsReferenceImages()}.
     * Callers send at most this many (the most relevant ones).
     */
    default int maxReferenceImages() {
        return 1;
    }
}
