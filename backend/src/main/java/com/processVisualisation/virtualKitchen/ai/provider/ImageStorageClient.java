package com.processVisualisation.virtualKitchen.ai.provider;

/**
 * Common contract implemented by storage backends (Supabase) used to
 * persist generated images for the Virtual Kitchen application and obtain a
 * publicly accessible URL for them.
 */
public interface ImageStorageClient {

    /**
     * Uploads image data to the storage backend at the given path.
     *
     * @param data the raw image bytes to upload
     * @param mimeType the MIME type of the image data
     * @param path the destination path/key within the storage backend
     * @return the publicly accessible URL of the uploaded image
     */
    String upload(
            byte[] data,
            String mimeType,
            String path
    );

    /** An image read back from storage. */
    record StoredImage(String mimeType, byte[] data) {
    }

    /**
     * Reads back an image previously stored by {@link #upload}, by the URL it returned. Backends that can't read
     * back throw {@link UnsupportedOperationException} (the default).
     *
     * @param url the URL {@link #upload} returned
     * @return the image bytes and MIME type
     */
    default StoredImage download(String url) {
        throw new UnsupportedOperationException("This image storage cannot read images back");
    }
}
