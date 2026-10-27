package com.processVisualisation.virtualKitchen.ai.provider.supabase;

import com.processVisualisation.virtualKitchen.ai.provider.ImageStorageClient;
import com.processVisualisation.virtualKitchen.ai.provider.config.SupabaseProperties;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiAuthenticationException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiCommunicationException;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link ImageStorageClient} implementation that uploads generated images
 * to Supabase Storage for the Virtual Kitchen application, so that
 * process-visualization images can be persisted and served from a public
 * URL.
 */
@Component
public class SupabaseImageStorageClient implements ImageStorageClient {

    private static final Logger log = LoggerFactory.getLogger(SupabaseImageStorageClient.class);

    private final RestClient restClient;
    private final SupabaseProperties properties;

    /**
     * Creates a client bound to the Supabase REST client and configuration.
     *
     * @param restClient the pre-configured REST client used to call the Supabase Storage API
     * @param properties the configured Supabase URL, service key, and storage bucket
     */
    public SupabaseImageStorageClient(
            @Qualifier("supabaseRestClient") RestClient restClient,
            SupabaseProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * Uploads image bytes to the configured Supabase Storage bucket at the
     * given path and returns the resulting public URL.
     * <p>
     * Transport failures are translated into the typed {@code AiClientException}
     * hierarchy so that {@code GlobalExceptionHandler} maps them to a sensible
     * status and, more importantly, so the caller can record a meaningful cause
     * rather than a bare {@code HttpClientErrorException} message.
     * <p>
     * Note that this upload is deliberately <em>not</em> performed inside the
     * {@code AiWork} lambda that {@code AiRequestQueueService} retries: doing so
     * would place a storage failure back inside the credit-consuming region,
     * where a retry re-runs the paid generation. The generated payload is staged
     * in the AI artifact store before this call, and {@code AiArtifactRecoveryJob}
     * is what re-drives a failed upload — over the payload that was already paid
     * for. See {@code AI_ARTIFACT_STORE_DESIGN.md} §10.
     *
     * @param data the raw image bytes to upload
     * @param mimeType the MIME type of the image data
     * @param path the destination path/key within the storage bucket
     * @return the publicly accessible URL of the uploaded image
     * @throws AiAuthenticationException if Supabase rejects the service key (401/403)
     * @throws AiCommunicationException if Supabase returns any other HTTP error, or is misconfigured
     * @throws AiTimeoutException if the request times out or the host cannot be reached
     */
    @Override
    public String upload(byte[] data, String mimeType, String path) {
        long startedAt = System.nanoTime();
        int bytes = data == null ? 0 : data.length;
        try {
            String url = doUpload(data, mimeType, path);
            log.debug("event=image_upload_stored provider=supabase bytes={} durationMs={}", bytes, elapsedMs(startedAt));
            return url;
        } catch (RuntimeException ex) {
            // WARN, no stack: the caller decides whether this fails the step (the payload stays recoverable).
            log.warn("event=image_upload_failed provider=supabase bytes={} durationMs={} errorType={} status={}",
                    bytes, elapsedMs(startedAt), ex.getClass().getSimpleName(), httpStatusOf(ex));
            throw ex;
        }
    }

    /**
     * Reads back an image this client uploaded, by the public URL {@link #upload} returned. Only URLs under this
     * bucket's public prefix are fetched, so a stored URL can never make the server request another host.
     *
     * @throws AiCommunicationException if the URL isn't one of this bucket's, or Supabase answers with an error
     * @throws AiTimeoutException if the request times out or the host cannot be reached
     */
    @Override
    public StoredImage download(String url) {
        validateConfiguration();
        String prefix = properties.getUrl() + "/storage/v1/object/public/" + properties.getBucket() + "/";
        if (url == null || !url.startsWith(prefix)) {
            throw new AiCommunicationException("Not an image of this storage bucket");
        }
        long startedAt = System.nanoTime();
        try {
            var response = restClient.get()
                    .uri(java.net.URI.create(url))
                    .retrieve()
                    .toEntity(byte[].class);
            byte[] data = response.getBody();
            if (data == null || data.length == 0) {
                throw new AiCommunicationException("Supabase storage returned an empty image");
            }
            String mimeType = response.getHeaders().getContentType() != null
                    ? response.getHeaders().getContentType().toString()
                    : "image/png";
            log.debug("event=image_download_completed provider=supabase bytes={} durationMs={}", data.length, elapsedMs(startedAt));
            return new StoredImage(mimeType, data);
        } catch (RestClientResponseException ex) {
            throw new AiCommunicationException("Supabase storage download failed with status: " + ex.getStatusCode(), ex);
        } catch (ResourceAccessException ex) {
            throw new AiTimeoutException("Supabase storage download timed out or could not connect", ex);
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private static Integer httpStatusOf(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof RestClientResponseException rre) {
                return rre.getStatusCode().value();
            }
        }
        return null;
    }

    private String doUpload(byte[] data, String mimeType, String path) {
        validateConfiguration();
        try {
            restClient.post()
                    .uri(
                            "/storage/v1/object/{bucket}/{path}",
                            properties.getBucket(),
                            path
                    )
                    .header(
                            "Authorization",
                            "Bearer " + properties.getServiceKey()
                    )
                    .header(
                            "apikey",
                            properties.getServiceKey()
                    )
                    .header(
                            "Content-Type",
                            mimeType
                    )
                    .body(data)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new AiAuthenticationException("Supabase storage authentication failed", ex);
            }
            throw new AiCommunicationException(
                    "Supabase storage upload failed with status: " + ex.getStatusCode(), ex);
        } catch (ResourceAccessException ex) {
            throw new AiTimeoutException("Supabase storage upload timed out or could not connect", ex);
        } catch (IllegalArgumentException ex) {
            throw new AiCommunicationException("Supabase storage configuration is invalid", ex);
        }

        return properties.getUrl()
                + "/storage/v1/object/public/"
                + properties.getBucket()
                + "/"
                + path;
    }

    /**
     * Fails fast with a typed exception when the Supabase connection details are
     * missing, rather than letting a blank bucket or service key surface as an
     * opaque 4xx from the storage API.
     */
    private void validateConfiguration() {
        if (!StringUtils.hasText(properties.getUrl())
                || !StringUtils.hasText(properties.getServiceKey())
                || !StringUtils.hasText(properties.getBucket())) {
            throw new AiCommunicationException(
                    "Supabase storage is not configured (url, service-key and bucket are all required)");
        }
    }
}